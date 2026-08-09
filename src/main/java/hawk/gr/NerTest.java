package hawk.gr;

import java.util.List;

/**
 * Standalone test for the NER ONNX inference engine.
 * <p>
 * Tests the extract() method against known inputs.
 */
public class NerTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Loading NER model...");
        long t0 = System.currentTimeMillis();

        try (NerONNXInference ner = new NerONNXInference()) {
            long t1 = System.currentTimeMillis();
            System.out.printf("Model loaded in %d ms%n%n", t1 - t0);

            String[] cases = {
                "小米黑色手机壳",
                "冬天加厚羽绒服",
                "女士显瘦牛仔裤春秋款",
                "儿童防晒帽户外遮阳",
                "真皮棕色手提包商务风",
                "不锈钢保温杯大容量500ml",
                "纯棉白色T恤短袖夏季",
            };

            for (String text : cases) {
                List<NerClient.NerEntity> entities = ner.extract(text);
                System.out.printf("  \"%s\"%n", text);
                if (entities.isEmpty()) {
                    System.out.println("    (no entities)");
                } else {
                    for (NerClient.NerEntity e : entities) {
                        System.out.printf("    → %s: %s%n", e.type(), e.span());
                    }
                }
                System.out.println();
            }
        }
        System.out.println("Done!");
    }
}
