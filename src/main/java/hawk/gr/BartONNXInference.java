package hawk.gr;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.*;
import java.util.*;

/**
 * Spring Boot 命令行应用：读取用户输入，输出 BART encoder 和 decoder 的推理结果。
 */
@SpringBootApplication
public class BartONNXInference implements CommandLineRunner, AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession encoderSession;
    private final OrtSession decoderSession;
    private final HuggingFaceTokenizer tokenizer;

    private static final int EOS_TOKEN_ID = 102;
    private static final int DECODER_START_TOKEN_ID = 102;
    private static final int FIXED_SEQ_LEN = 64;

    public BartONNXInference() throws Exception {
        Path dir = Paths.get("model");

        tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve("tokenizer.json"));

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = OnnxUtils.createSessionOptions();

        encoderSession = env.createSession(dir.resolve("bart_encoder.onnx").toString(), opts);
        decoderSession = env.createSession(dir.resolve("bart_decoder.onnx").toString(), opts);
    }

    // ==================== 公开 API ====================

    /** 对输入文本做 encoder，返回 hidden states (1, seq_len, d_model) */
    public float[][][] encode(String text) throws OrtException {
        Encoding encoding = tokenizer.encode(text);
        long[] inputIds = pad(encoding.getIds());
        long[] attentionMask = pad(encoding.getAttentionMask());

        return runEncoder(new long[][]{inputIds}, new long[][]{attentionMask});
    }

    /**
     * 单步 decoder：输入当前 token 和 encoder 输出，返回 logits。
     * @param tokenId 当前步的 token id
     * @param encoderHidden encoder 输出 (1, seq_len, d_model)
     * @param encoderMask encoder 的 attention mask (1, seq_len)
     * @return logits (1, 1, vocab_size)
     */
    public float[][][] decodeStep(long tokenId, float[][][] encoderHidden,
                                   long[][] encoderMask) throws OrtException {
        return runDecoder(new long[][]{{tokenId}}, encoderHidden, encoderMask);
    }

    /**
     * Greedy autoregressive generation (encoder → decoder loop).
     *
     * @param text     input text
     * @param maxSteps max decoder steps (default 64 for SID generation)
     * @return generated token IDs
     */
    public long[] generate(String text, int maxSteps) throws OrtException {
        Encoding encoding = tokenizer.encode(text);
        long[] inputIds = pad(encoding.getIds());
        long[] encMask = pad(encoding.getAttentionMask());  // use REAL mask!

        float[][][] encHidden = runEncoder(
            new long[][]{inputIds}, new long[][]{encMask});

        List<Long> generated = new ArrayList<>();
        generated.add((long) DECODER_START_TOKEN_ID);

        for (int step = 0; step < maxSteps; step++) {
            // Pass the FULL generated sequence — BART decoder needs
            // all previous tokens for causal self-attention.
            long[] inputSeq = generated.stream().mapToLong(Long::longValue).toArray();
            float[][][] logits = runDecoder(
                new long[][]{inputSeq}, encHidden, new long[][]{encMask});

            // Take LAST position's logits (next-token prediction)
            float[] stepLogits = logits[0][logits[0].length - 1];
            long nextToken = argmax(stepLogits);
            generated.add(nextToken);

            if (nextToken == EOS_TOKEN_ID) break;
        }

        return generated.stream().mapToLong(Long::longValue).toArray();
    }

    /** Convenience: generate and decode to String. */
    public String generateText(String text, int maxSteps) throws OrtException {
        long[] tokenIds = generate(text, maxSteps);
        return tokenizer.decode(tokenIds);
    }

    /**
     * Route a query SID to item SID (greedy).
     * This is the core Stage 2 operation: querySID → itemSID.
     */
    public String routeQuerySidToItemSid(String querySid) throws OrtException {
        long[] tokenIds = generate(querySid, 64);
        return decodeSidTokens(tokenIds);
    }

    /**
     * Beam-search: route a query SID to top-K item SIDs.
     * Uses unconstrained beam search over the SID token alphabet.
     *
     * <p>内部 beam 宽度取 {@code max(k, 3)}：宽度 1 即贪心（每步 argmax），无法比较多条前缀、
     * 拿不到累计最优；设下限 3 后 k=1 也按整条序列累计概率搜索，返回累计最优的单条，
     * 与 k≥2 看到的最优路径一致（代价是 k=1 的 decoder 调用数从 1 条变 3 条/步）。
     *
     * @param querySid query SID string e.g. "&lt;a_579&gt;&lt;b_0&gt;&lt;c_534&gt;..."
     * @param k        number of item SID candidates to return
     * @return top-K item SID strings, sorted by beam score descending
     */
    public List<String> routeQuerySidToItemSids(String querySid, int k) throws OrtException {
        // Encoder
        Encoding encoding = tokenizer.encode(querySid);
        long[] inputIds = pad(encoding.getIds());
        long[] encMask = pad(encoding.getAttentionMask());
        float[][][] encHidden = runEncoder(
            new long[][]{inputIds}, new long[][]{encMask});

        // Beam: each beam = {tokenIds[], logProb, done}
        List<Beam> beams = new ArrayList<>();
        beams.add(new Beam(new long[]{DECODER_START_TOKEN_ID}, 0.0, false));

        // 内部搜索宽度下限 3：k=1 时也做真正的 beam 搜索（非贪心），按累计概率取最优
        int beamWidth = Math.max(k, 3);

        int vocabSize = 0; // will be set on first decoder step

        // 模型输出约定 [SEP]→[CLS]→a b c d e f g h→[SEP]：第 1 步生成 [CLS]，
        // 所以 8 个 SID token 要 10 步才够（CLS + 8 SID + EOS）。
        for (int step = 0; step < 10; step++) {
            List<Beam> candidates = new ArrayList<>();

            for (Beam beam : beams) {
                if (beam.done) {
                    candidates.add(beam);
                    continue;
                }

                float[][][] logits = runDecoder(
                    new long[][]{beam.tokens}, encHidden, new long[][]{encMask});
                float[] stepLogits = logits[0][logits[0].length - 1];

                if (vocabSize == 0) vocabSize = stepLogits.length;

                // Top-N expansion per beam
                int expandN = Math.min(beamWidth * 4, vocabSize);
                int[] topN = topK(stepLogits, expandN);

                for (int tokenId : topN) {
                    double tokenLogProb = Math.log(Math.max(softmaxSingle(stepLogits, tokenId), 1e-12));
                    long[] newTokens = Arrays.copyOf(beam.tokens, beam.tokens.length + 1);
                    newTokens[newTokens.length - 1] = tokenId;
                    double newScore = beam.score + tokenLogProb;
                    boolean done = (tokenId == EOS_TOKEN_ID);
                    candidates.add(new Beam(newTokens, newScore, done));
                }
            }

            // Keep top-beamWidth beams
            candidates.sort((a, b) -> Double.compare(b.score, a.score));
            int keep = Math.min(beamWidth, candidates.size());
            beams = new ArrayList<>(candidates.subList(0, keep));

            // If all beams hit EOS, stop early
            if (beams.stream().allMatch(b -> b.done)) break;
        }

        // Convert beams to SID strings, deduplicate, cap at top-k (beams 按 score 降序)
        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Beam beam : beams) {
            if (results.size() >= k) break;
            String sid = decodeSidTokens(beam.tokens);
            if (!sid.isEmpty() && seen.add(sid)) {
                results.add(sid);
            }
        }
        return results;
    }

    /** Decode token IDs to SID string, filtering special tokens. */
    private String decodeSidTokens(long[] tokenIds) {
        List<Long> sidTokens = new ArrayList<>();
        for (long id : tokenIds) {
            if (id > 102) sidTokens.add(id);
        }
        long[] filtered = sidTokens.stream().mapToLong(Long::longValue).toArray();
        return tokenizer.decode(filtered).replace(" ", "");
    }

    /** Beam state for beam search. */
    private static class Beam {
        final long[] tokens;
        final double score;
        final boolean done;

        Beam(long[] tokens, double score, boolean done) {
            this.tokens = tokens; this.score = score; this.done = done;
        }
    }

    /** Softmax for a single token index. */
    private static double softmaxSingle(float[] logits, int idx) {
        double maxLogit = Double.NEGATIVE_INFINITY;
        for (float v : logits) {
            if (v > maxLogit) maxLogit = v;
        }
        double sum = 0.0;
        for (float v : logits) {
            sum += Math.exp(v - maxLogit);
        }
        return Math.exp(logits[idx] - maxLogit) / sum;
    }

    // ==================== Spring Boot CLI ====================

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("  BART ONNX 推理 — 输入文本，查看 encoder/decoder 输出");
        System.out.println("  输入 'exit' 退出");
        System.out.println("=".repeat(60));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\n> ");
                String text = scanner.nextLine().trim();
                if (text.isEmpty()) continue;
                if ("exit".equalsIgnoreCase(text)) break;

                try {
                    // 1. Tokenize
                    Encoding encoding = tokenizer.encode(text);
                    long[] rawIds = encoding.getIds();
                    long[] rawMask = encoding.getAttentionMask();
                    System.out.println("\n[Tokenizer]");
                    System.out.printf("  tokens: %s%n", Arrays.toString(encoding.getTokens()));
                    System.out.printf("  ids   : %s%n", Arrays.toString(rawIds));

                    long[] inputIds = pad(rawIds);
                    long[] attentionMask = pad(rawMask);

                    // 2. Encoder
                    long t0 = System.currentTimeMillis();
                    float[][][] encHidden = runEncoder(
                        new long[][]{inputIds}, new long[][]{attentionMask});
                    long t1 = System.currentTimeMillis();
                    int dModel = encHidden[0][0].length;

                    System.out.println("\n[Encoder Output]");
                    System.out.printf("  shape      : (1, %d, %d)%n", FIXED_SEQ_LEN, dModel);
                    System.out.printf("  time       : %d ms%n", t1 - t0);
                    System.out.printf("  mean       : %.6f%n", mean(encHidden[0]));
                    System.out.printf("  std        : %.6f%n", std(encHidden[0]));
                    System.out.printf("  first token: [%.4f, %.4f, %.4f, ...]%n",
                        encHidden[0][0][0], encHidden[0][0][1], encHidden[0][0][2]);

                    // 3. Decoder — 逐步输出每一步的 top-5 token
                    System.out.println("\n[Decoder Steps]");
                    List<Long> generated = new ArrayList<>();
                    generated.add((long) DECODER_START_TOKEN_ID);

                    for (int step = 0; step < 20; step++) {  // 最多 20 步
                        long t2 = System.currentTimeMillis();
                        // Pass FULL accumulated sequence — decoder needs
                        // all previous tokens for causal self-attention.
                        long[] inputSeq = generated.stream().mapToLong(Long::longValue).toArray();
                        float[][][] logits = runDecoder(
                            new long[][]{inputSeq}, encHidden,
                            new long[][]{attentionMask});
                        long t3 = System.currentTimeMillis();

                        // Take LAST position's logits (next-token prediction)
                        float[] stepLogits = logits[0][logits[0].length - 1];
                        int[] top5Idx = topK(stepLogits, 5);
                        long nextToken = top5Idx[0];  // greedy

                        long currentToken = generated.get(generated.size() - 1);
                        System.out.printf("  step %2d | token=%-6s (id=%d) | top-5: ",
                            step + 1,
                            tokenToStr(currentToken), currentToken);
                        for (int k = 0; k < 5; k++) {
                            int tid = top5Idx[k];
                            float prob = (float) Math.exp(stepLogits[tid]);
                            System.out.printf("%s(%.3f) ", tokenToStr(tid), prob);
                        }
                        System.out.printf("| %d ms%n", t3 - t2);

                        if (nextToken == EOS_TOKEN_ID) {
                            System.out.println("  → 遇到 [SEP]，解码结束");
                            break;
                        }
                        generated.add(nextToken);
                    }

                    // 4. 最终结果
                    String decoded = tokenizer.decode(
                        generated.stream().mapToLong(Long::longValue).toArray());
                    System.out.printf("%n[生成结果] %s%n", decoded);

                } catch (Exception e) {
                    System.err.println("ERROR: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        System.out.println("Bye!");
    }

    // ==================== 模型推理 ====================

    private float[][][] runEncoder(long[][] inputIds, long[][] attentionMask) throws OrtException {
        try (OnnxTensor in = OnnxTensor.createTensor(env, inputIds);
             OnnxTensor mask = OnnxTensor.createTensor(env, attentionMask)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", in);
            inputs.put("attention_mask", mask);
            OrtSession.Result r = encoderSession.run(inputs);
            float[][][] out = (float[][][]) r.get(0).getValue();
            r.close();
            return out;
        }
    }

    private float[][][] runDecoder(long[][] tokenIds, float[][][] encHidden,
                                    long[][] encMask) throws OrtException {
        try (OnnxTensor decIn = OnnxTensor.createTensor(env, tokenIds);
             OnnxTensor encH = OnnxTensor.createTensor(env, encHidden);
             OnnxTensor encM = OnnxTensor.createTensor(env, encMask)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", decIn);
            inputs.put("encoder_hidden_states", encH);
            inputs.put("encoder_attention_mask", encM);
            OrtSession.Result r = decoderSession.run(inputs);
            float[][][] out = (float[][][]) r.get(0).getValue();
            r.close();
            return out;
        }
    }

    // ==================== 工具方法 ====================

    private long[] pad(long[] arr) {
        long[] padded = new long[FIXED_SEQ_LEN];
        System.arraycopy(arr, 0, padded, 0, Math.min(arr.length, FIXED_SEQ_LEN));
        return padded;
    }

    private double mean(float[][] matrix) {
        double sum = 0;
        int count = 0;
        for (float[] row : matrix) {
            for (float v : row) { sum += v; count++; }
        }
        return sum / count;
    }

    private double std(float[][] matrix) {
        double m = mean(matrix);
        double sumSq = 0;
        int count = 0;
        for (float[] row : matrix) {
            for (float v : row) { sumSq += Math.pow(v - m, 2); count++; }
        }
        return Math.sqrt(sumSq / count);
    }

    private int[] topK(float[] array, int k) {
        Integer[] indices = new Integer[array.length];
        for (int i = 0; i < array.length; i++) indices[i] = i;
        Arrays.sort(indices, Comparator.comparingDouble(i -> -array[(int) i]));
        int[] result = new int[k];
        for (int i = 0; i < k; i++) result[i] = indices[i];
        return result;
    }

    private int argmax(float[] array) {
        int best = 0;
        for (int i = 1; i < array.length; i++) {
            if (array[i] > array[best]) best = i;
        }
        return best;
    }

    private String tokenToStr(long id) {
        String token = tokenizer.decode(new long[]{id});
        return token.isEmpty() ? "[" + id + "]" : token;
    }

    @Override
    public void close() throws Exception {
        encoderSession.close();
        decoderSession.close();
        env.close();
    }

    public static void main(String[] args) {
        SpringApplication.run(BartONNXInference.class, args);
    }
}
