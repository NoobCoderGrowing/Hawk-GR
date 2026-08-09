package hawk.gr;

import java.util.Arrays;
import java.util.List;

/**
 * End-to-end demo: attributes → KAE encode → querySID → BART route → itemSID.
 */
public class SidRoutingDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("  SID Routing Demo: KAE Encode → BART Route");
        System.out.println("=".repeat(60));

        // 1. Load KAE encoder
        System.out.println("\n[1] Loading KAE encoder...");
        KaeEncoder kae = new KaeEncoder();
        System.out.println("    Codebook entries: " + kae.getConfig().totalEntries());

        // 2. Load BART model
        System.out.println("\n[2] Loading BART ONNX model...");
        BartONNXInference bart = new BartONNXInference();
        System.out.println("    Model loaded.");

        // 3. Test: attributes → querySID → itemSID
        System.out.println("\n[3] Routing tests");

        Object[][] cases = {
            {Arrays.asList("产品_核心产品:上衣", "品牌:小米", "风格:休闲"),
             "上衣 + 小米 + 休闲"},
            {Arrays.asList("产品_核心产品:手机壳", "颜色_色彩:黑色"),
             "手机壳 + 黑色"},
            {Arrays.asList("产品_核心产品:裤", "功能功效:显瘦", "材质_面料:牛仔"),
             "裤 + 显瘦 + 牛仔"},
        };

        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            List<String> attrs = (List<String>) c[0];
            String desc = (String) c[1];

            String querySid = kae.encode(attrs);
            String itemSid = bart.routeQuerySidToItemSid(querySid);

            System.out.printf("  [%s]%n", desc);
            System.out.printf("    querySid: %s%n", querySid);
            System.out.printf("    itemSid:  %s%n", itemSid);
            System.out.println();
        }

        // 4. Test reserved slot
        System.out.println("[4] Reserved slot routing");
        String baseSid = kae.encode(Arrays.asList("产品_核心产品:上衣", "品牌:小米"));
        String withReserved = KaeEncoder.setReservedSlot(baseSid, 'd', 2000);
        String routed = bart.routeQuerySidToItemSid(withReserved);

        System.out.printf("  base querySid:  %s%n", baseSid);
        System.out.printf("  with reserved:  %s%n", withReserved);
        System.out.printf("  routed itemSid: %s%n", routed);

        // Check if reserved slot preserved
        int[] routedIndices = KaeEncoder.parseSid(routed);
        boolean dPreserved = routedIndices[3] == 2000;
        System.out.printf("  d_2000 preserved: %s%n", dPreserved ? "YES ✓" : "NO ✗");

        bart.close();
        System.out.println("\nDone!");
    }
}
