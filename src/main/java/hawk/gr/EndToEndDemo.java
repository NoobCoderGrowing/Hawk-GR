package hawk.gr;

import java.util.List;
import java.util.stream.Collectors;

/**
 * End-to-end demo: raw text → NER → KAE encode → querySID → BART route → itemSID.
 *
 * <pre>
 *   "小米黑色手机壳"
 *       ↓ NerONNXInference.extract()
 *   [品牌:小米, 颜色_色彩:黑色, 产品_核心产品:壳]
 *       ↓ KaeEncoder.encode()
 *   &lt;a_304&gt;&lt;b_1&gt;&lt;c_13&gt;&lt;d_0&gt;&lt;e_0&gt;&lt;f_5&gt;
 *       ↓ BartONNXInference.routeQuerySidToItemSid()
 *   &lt;a_158&gt;&lt;b_1&gt;&lt;c_13&gt;&lt;d_0&gt;&lt;e_0&gt;&lt;f_5&gt;
 * </pre>
 */
public class EndToEndDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("  End-to-End: Raw Text → Item SID");
        System.out.println("=".repeat(60));

        // 1. Load models
        System.out.println("\n[1] Loading NER model...");
        long t0 = System.currentTimeMillis();
        NerONNXInference ner = new NerONNXInference();
        System.out.printf("    NER loaded in %d ms%n", System.currentTimeMillis() - t0);

        System.out.println("[2] Loading KAE encoder...");
        KaeEncoder kae = new KaeEncoder();
        System.out.println("    Codebook entries: " + kae.getConfig().totalEntries());

        System.out.println("[3] Loading BART model...");
        t0 = System.currentTimeMillis();
        BartONNXInference bart = new BartONNXInference();
        System.out.printf("    BART loaded in %d ms%n", System.currentTimeMillis() - t0);

        // 2. Test cases
        System.out.println("\n[4] Routing tests");

        String[] queries = {
            "小米黑色手机壳",
            "冬季加厚羽绒服男款",
            "女士显瘦牛仔裤",
        };

        for (String query : queries) {
            System.out.printf("%n  Query: \"%s\"%n", query);

            // Step 1: NER
            List<NerEntity> entities = ner.extract(query);
            System.out.println("    NER entities:");
            for (NerEntity e : entities) {
                System.out.printf("      %s: %s%n", e.type(), e.span());
            }

            // Step 2: Convert to KAE format: "prefix:value"
            List<String> attrs = entities.stream()
                .map(e -> e.type() + ":" + e.span())
                .collect(Collectors.toList());
            System.out.println("    Attributes: " + attrs);

            // Step 3: KAE encode → query SID
            String querySid = kae.encode(attrs);
            System.out.println("    querySid: " + querySid);

            // Step 4: BART route → item SID
            String itemSid = bart.routeQuerySidToItemSid(querySid);
            System.out.println("    itemSid:  " + itemSid);
        }

        // 3. Reserved slot test
        System.out.println("\n[5] Reserved slot routing");
        List<NerEntity> ent = ner.extract("小米上衣休闲风");
        String baseSid = kae.encode(
            ent.stream().map(e -> e.type() + ":" + e.span()).collect(Collectors.toList()));
        String withReserved = KaeEncoder.setReservedSlot(baseSid, 'd', 2000);
        String routed = bart.routeQuerySidToItemSid(withReserved);
        int[] routedIdx = KaeEncoder.parseSid(routed);
        System.out.printf("  base:     %s%n", baseSid);
        System.out.printf("  reserved: %s%n", withReserved);
        System.out.printf("  routed:   %s%n", routed);
        System.out.printf("  d_2000 preserved: %s%n", routedIdx[3] == 2000 ? "YES ✓" : "NO ✗");

        ner.close();
        bart.close();
        System.out.println("\nDone!");
    }
}
