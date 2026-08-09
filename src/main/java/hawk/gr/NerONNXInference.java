package hawk.gr;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Pure Java NER (Named Entity Recognition) inference.
 * <p>
 * Uses BERT encoder (ONNX) + linear head + CRF Viterbi decoding.
 * No Python dependency at runtime.
 *
 * <h3>Architecture</h3>
 * <pre>
 *   text → Tokenizer → BERT ONNX → hidden [seq, 768]
 *        → linear [207, 768] → logits [seq, 207]
 *        → CRF Viterbi decode → label IDs [seq]
 *        → BIO decoding → List{@code <NerEntity>}
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   try (NerONNXInference ner = new NerONNXInference()) {
 *       List{@code <NerEntity>} entities = ner.extract("小米黑色手机壳");
 *       // → [{type: "品牌", span: "小米"}, ...]
 *   }
 * </pre>
 */
public class NerONNXInference implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    // Linear head
    private final float[][] linearWeight;     // [207][768]
    private final float[] linearBias;         // [207]

    // CRF
    private final float[] startTrans;         // [207]
    private final float[] endTrans;           // [207]
    private final float[][] transitions;      // [207][207]

    // Labels
    private final String[] id2label;          // id → label (e.g., "B-品牌")
    private final int numLabels;

    public NerONNXInference() throws Exception {
        this(Paths.get("src/main/resources/ner_model"));
    }

    public NerONNXInference(Path modelDir) throws Exception {
        // ——— ONNX session ———
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        this.session = env.createSession(
            modelDir.resolve("bert_encoder.onnx").toString(), opts);

        // ——— Tokenizer ———
        this.tokenizer = HuggingFaceTokenizer.newInstance(
            modelDir.resolve("tokenizer/tokenizer.json"));

        // ——— Load weights ———
        this.linearWeight = loadMatrix(modelDir.resolve("linear_weight.bin"), 207, 768);
        this.linearBias   = loadVector(modelDir.resolve("linear_bias.bin"), 207);
        this.startTrans   = loadVector(modelDir.resolve("start_trans.bin"), 207);
        this.endTrans     = loadVector(modelDir.resolve("end_trans.bin"), 207);
        this.transitions  = loadMatrix(modelDir.resolve("transitions.bin"), 207, 207);

        // ——— Labels ———
        Gson gson = new Gson();
        try (Reader r = Files.newBufferedReader(modelDir.resolve("label_list.json"))) {
            this.id2label = gson.fromJson(r, String[].class);
        }
        this.numLabels = id2label.length;
    }

    // ==================== Public API ====================

    /**
     * Extract named entities from raw Chinese text.
     *
     * @param text input text e.g. "小米黑色手机壳"
     * @return list of entities with type, span, and probability
     */
    public List<NerClient.NerEntity> extract(String text) throws OrtException {
        // 1. Tokenize
        Encoding encoding = tokenizer.encode(text);
        long[] tokenIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();
        String[] tokens = encoding.getTokens();

        // 2. BERT encoder
        float[][][] hidden = runEncoder(tokenIds, attentionMask);
        int seqLen = (int) encoding.getIds().length;

        // 3. Linear projection → logits [seqLen, numLabels]
        float[][] logits = linearProject(hidden[0], seqLen);

        // 4. CRF Viterbi decode
        int[] bestPath = viterbiDecode(logits, seqLen, (int) attentionMask.length);

        // 5. BIO → entities
        return bioToEntities(tokens, bestPath, seqLen);
    }

    // ==================== BERT ONNX ====================

    private float[][][] runEncoder(long[] tokenIds, long[] attentionMask) throws OrtException {
        int seqLen = tokenIds.length;
        long[] paddedIds = new long[seqLen];
        long[] paddedMask = new long[seqLen];
        System.arraycopy(tokenIds, 0, paddedIds, 0, seqLen);
        System.arraycopy(attentionMask, 0, paddedMask, 0, seqLen);

        try (OnnxTensor inIds = OnnxTensor.createTensor(env, new long[][]{paddedIds});
             OnnxTensor inMask = OnnxTensor.createTensor(env, new long[][]{paddedMask})) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inIds);
            inputs.put("attention_mask", inMask);
            OrtSession.Result r = session.run(inputs);
            float[][][] out = (float[][][]) r.get(0).getValue();
            r.close();
            return out;
        }
    }

    // ==================== Linear Projection ====================

    /**
     * hidden [seqLen, 768] @ weight^T [768, 207] + bias [207] → logits [seqLen, 207]
     */
    private float[][] linearProject(float[][] hidden, int seqLen) {
        int hiddenSize = linearWeight[0].length; // 768
        float[][] logits = new float[seqLen][numLabels];

        for (int i = 0; i < seqLen; i++) {
            float[] h = hidden[i];
            float[] l = logits[i];
            for (int j = 0; j < numLabels; j++) {
                float sum = linearBias[j];
                float[] w = linearWeight[j];
                for (int k = 0; k < hiddenSize; k++) {
                    sum += h[k] * w[k];
                }
                l[j] = sum;
            }
        }
        return logits;
    }

    // ==================== CRF Viterbi Decode ====================

    /**
     * Standard CRF Viterbi algorithm with start/end transitions.
     *
     * @param logits emission scores [seqLen, numLabels]
     * @param seqLen effective sequence length
     * @param maskLen attention mask length (for identifying valid tokens)
     * @return best label path [seqLen]
     */
    private int[] viterbiDecode(float[][] logits, int seqLen, int maskLen) {
        // score[i][j] = max score of path ending at position i with label j
        float[][] score = new float[seqLen][numLabels];
        int[][] back = new int[seqLen][numLabels];

        // Initialize position 0
        for (int j = 0; j < numLabels; j++) {
            score[0][j] = startTrans[j] + logits[0][j];
        }

        // Forward
        for (int i = 1; i < seqLen; i++) {
            for (int j = 0; j < numLabels; j++) {
                float maxScore = Float.NEGATIVE_INFINITY;
                int maxK = 0;
                for (int k = 0; k < numLabels; k++) {
                    float s = score[i - 1][k] + transitions[k][j];
                    if (s > maxScore) {
                        maxScore = s;
                        maxK = k;
                    }
                }
                score[i][j] = maxScore + logits[i][j];
                back[i][j] = maxK;
            }
        }

        // Find best final label
        float maxFinal = Float.NEGATIVE_INFINITY;
        int bestLast = 0;
        for (int j = 0; j < numLabels; j++) {
            float s = score[seqLen - 1][j] + endTrans[j];
            if (s > maxFinal) {
                maxFinal = s;
                bestLast = j;
            }
        }

        // Backtrack
        int[] bestPath = new int[seqLen];
        bestPath[seqLen - 1] = bestLast;
        for (int i = seqLen - 2; i >= 0; i--) {
            bestPath[i] = back[i + 1][bestPath[i + 1]];
        }

        return bestPath;
    }

    // ==================== BIO → Entities ====================

    /**
     * Convert BIO-encoded label sequence to entity list.
     * Schema: B-begin, I-inside, E-end, S-single, O-outside.
     * Skips special tokens like [CLS], [SEP], [PAD], [UNK].
     */
    private List<NerClient.NerEntity> bioToEntities(String[] tokens, int[] bestPath, int seqLen) {
        List<NerClient.NerEntity> entities = new ArrayList<>();

        int i = 0;
        while (i < seqLen) {
            // Skip special tokens
            if (isSpecialToken(tokens[i])) {
                i++;
                continue;
            }

            String label = id2label[bestPath[i]];
            if ("O".equals(label)) {
                i++;
                continue;
            }

            String prefix = label.substring(0, 1); // B, I, E, or S
            String type = label.substring(2);       // e.g., "品牌"

            if ("S".equals(prefix)) {
                // Single-token entity
                entities.add(new NerClient.NerEntity(type, tokens[i], 0.0));
                i++;
            } else if ("B".equals(prefix)) {
                // Multi-token entity: B ... I ... E
                StringBuilder span = new StringBuilder();
                int start = i;
                String entityType = type;
                while (i < seqLen) {
                    if (isSpecialToken(tokens[i])) { i++; continue; }
                    String curLabel = id2label[bestPath[i]];
                    String curPrefix = curLabel.substring(0, 1);
                    if (curPrefix.equals("B") && i > start) break; // new entity starts
                    if (curPrefix.equals("S") && i > start) break; // single entity
                    if (curPrefix.equals("O")) break;               // outside
                    span.append(tokens[i]);
                    if (curPrefix.equals("E")) {
                        i++;
                        break; // entity complete
                    }
                    i++;
                }
                entities.add(new NerClient.NerEntity(entityType, span.toString(), 0.0));
            } else {
                // I or E without preceding B — treat as single, skip
                if (!isSpecialToken(tokens[i])) {
                    entities.add(new NerClient.NerEntity(type, tokens[i], 0.0));
                }
                i++;
            }
        }
        return entities;
    }

    private static boolean isSpecialToken(String token) {
        return token == null || token.isEmpty()
            || (token.startsWith("[") && token.endsWith("]"));
    }

    // ==================== I/O Helpers ====================

    private static float[] loadVector(Path path, int expectedLen) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != expectedLen * 4) {
            throw new IOException("Expected " + (expectedLen * 4) + " bytes, got " + bytes.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[expectedLen];
        for (int i = 0; i < expectedLen; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    private static float[][] loadMatrix(Path path, int rows, int cols) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != rows * cols * 4) {
            throw new IOException("Expected " + (rows * cols * 4) + " bytes, got " + bytes.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[][] mat = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                mat[i][j] = buf.getFloat();
            }
        }
        return mat;
    }

    @Override
    public void close() throws Exception {
        session.close();
        env.close();
        tokenizer.close();
    }
}
