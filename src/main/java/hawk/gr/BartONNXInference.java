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
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);

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
                    long tokenId = DECODER_START_TOKEN_ID;
                    List<Long> generated = new ArrayList<>();
                    generated.add(tokenId);

                    for (int step = 0; step < 20; step++) {  // 最多 20 步
                        long t2 = System.currentTimeMillis();
                        float[][][] logits = runDecoder(
                            new long[][]{{tokenId}}, encHidden,
                            new long[][]{attentionMask});
                        long t3 = System.currentTimeMillis();

                        // 取 top-5
                        float[] stepLogits = logits[0][0];
                        int[] top5Idx = topK(stepLogits, 5);
                        long nextToken = top5Idx[0];  // greedy

                        System.out.printf("  step %2d | token=%-6s (id=%d) | top-5: ",
                            step + 1,
                            tokenToStr(tokenId), tokenId);
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
                        tokenId = nextToken;
                        generated.add(tokenId);
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
