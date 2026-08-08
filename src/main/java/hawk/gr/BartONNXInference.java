package hawk.gr;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import java.nio.file.*;
import java.util.*;

/**
 * BART ONNX 推理引擎。
 *
 * 使用方式:
 *   BartONNXInference engine = new BartONNXInference("model");
 *   String result = engine.generate("今天天气很好");
 */
public class BartONNXInference implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession encoderSession;
    private final OrtSession decoderSession;
    private final HuggingFaceTokenizer tokenizer;

    // 特殊 token id (来自 config.json)
    private static final int EOS_TOKEN_ID = 102;         // [SEP]
    private static final int DECODER_START_TOKEN_ID = 102;
    private static final int MAX_LENGTH = 128;

    public BartONNXInference(String modelDir) throws Exception {
        Path dir = Paths.get(modelDir);

        // 加载 HuggingFace tokenizer
        tokenizer = HuggingFaceTokenizer.newInstance(
            dir.resolve("tokenizer.json")
        );

        // 加载 ONNX 模型
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);

        encoderSession = env.createSession(dir.resolve("bart_encoder.onnx").toString(), opts);
        decoderSession = env.createSession(dir.resolve("bart_decoder.onnx").toString(), opts);
    }

    // ==================== 对外接口 ====================

    /** 单条文本生成 */
    public String generate(String text) throws OrtException {
        return generate(text, MAX_LENGTH);
    }

    private static final int FIXED_SEQ_LEN = 64;  // 和 ONNX 导出时一致

    /** 单条文本生成 (指定最大长度) */
    public String generate(String text, int maxLen) throws OrtException {
        // 1. Tokenize
        Encoding encoding = tokenizer.encode(text);
        long[] rawIds = encoding.getIds();
        long[] rawMask = encoding.getAttentionMask();

        // 2. Pad 到固定长度 64（ONNX 导出时的序列长度）
        long[] inputIds = new long[FIXED_SEQ_LEN];
        long[] attentionMask = new long[FIXED_SEQ_LEN];
        int copyLen = Math.min(rawIds.length, FIXED_SEQ_LEN);
        System.arraycopy(rawIds, 0, inputIds, 0, copyLen);
        System.arraycopy(rawMask, 0, attentionMask, 0, copyLen);

        // 3. Encoder
        long[][] batchInputIds = {inputIds};
        long[][] batchMask = {attentionMask};
        float[][][] encoderOutput = runEncoder(batchInputIds, batchMask);

        // 4. Decoder auto-regressive
        long[][] generated = greedyDecode(encoderOutput, batchMask, maxLen);

        // 5. Detokenize
        return tokenizer.decode(generated[0]);
    }

    // ==================== Encoder ====================

    private float[][][] runEncoder(long[][] inputIds, long[][] attentionMask) throws OrtException {
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, attentionMask)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputTensor);
            inputs.put("attention_mask", maskTensor);

            OrtSession.Result result = encoderSession.run(inputs);
            float[][][] hidden = (float[][][]) result.get(0).getValue();
            result.close();
            return hidden;
        }
    }

    // ==================== Decoder (auto-regressive) ====================

    private long[][] greedyDecode(float[][][] encoderHidden, long[][] encoderMask,
                                   int maxLen) throws OrtException {
        int batchSize = encoderHidden.length;
        boolean[] finished = new boolean[batchSize];

        // 每个样本的已生成 token 序列 (从 decoder_start_token_id 开始)
        List<List<Long>> sequences = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            List<Long> seq = new ArrayList<>();
            seq.add((long) DECODER_START_TOKEN_ID);
            sequences.add(seq);
        }

        for (int step = 0; step < maxLen; step++) {
            // 构建当前步的 decoder 输入: (batch, 1)
            long[][] decoderInputIds = new long[batchSize][1];
            int activeCount = 0;
            for (int i = 0; i < batchSize; i++) {
                if (!finished[i]) {
                    List<Long> seq = sequences.get(i);
                    decoderInputIds[i][0] = seq.get(seq.size() - 1);
                    activeCount++;
                }
            }
            if (activeCount == 0) break;

            try (OnnxTensor decInput = OnnxTensor.createTensor(env, decoderInputIds);
                 OnnxTensor encHidden = OnnxTensor.createTensor(env, encoderHidden);
                 OnnxTensor encMask = OnnxTensor.createTensor(env, encoderMask)) {

                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", decInput);
                inputs.put("encoder_hidden_states", encHidden);
                inputs.put("encoder_attention_mask", encMask);

                OrtSession.Result result = decoderSession.run(inputs);
                float[][][] logits = (float[][][]) result.get(0).getValue();
                result.close();

                for (int i = 0; i < batchSize; i++) {
                    if (finished[i]) continue;
                    float[] lastLogits = logits[i][0];
                    long nextToken = argmax(lastLogits);

                    if (nextToken == EOS_TOKEN_ID) {
                        finished[i] = true;
                    } else {
                        sequences.get(i).add(nextToken);
                    }
                }
            }
        }

        long[][] result = new long[batchSize][];
        for (int i = 0; i < batchSize; i++) {
            List<Long> seq = sequences.get(i);
            result[i] = seq.stream().mapToLong(Long::longValue).toArray();
        }
        return result;
    }

    private long argmax(float[] array) {
        int bestIdx = 0;
        float bestVal = array[0];
        for (int i = 1; i < array.length; i++) {
            if (array[i] > bestVal) {
                bestIdx = i;
                bestVal = array[i];
            }
        }
        return bestIdx;
    }

    @Override
    public void close() throws Exception {
        encoderSession.close();
        decoderSession.close();
        env.close();
    }

    // ==================== 测试入口 ====================
    public static void main(String[] args) throws Exception {
        try (BartONNXInference engine = new BartONNXInference("model")) {
            String text = "今天天气很好";
            System.out.println("Input : " + text);
            String result = engine.generate(text);
            System.out.println("Output: " + result);
        }
    }
}
