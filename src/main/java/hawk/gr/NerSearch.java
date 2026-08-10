package hawk.gr;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Interactive CLI using NER + KAE encoding path (vs DictEncoder).
 *
 * <pre>
 *   query → NER extract → KAE encode → query SID → BART → items
 * </pre>
 *
 * Usage: {@code ./run.sh hawk.gr.NerSearch}
 */
public class NerSearch {

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════╗");
        System.out.println("  ║     Hawk-GR 商品检索 (NER+KAE 路径)      ║");
        System.out.println("  ║     Query → NER → KAE → BART → Items     ║");
        System.out.println("  ╚══════════════════════════════════════════╝");
        System.out.println();

        // Load models
        long t0 = System.currentTimeMillis();

        System.out.print("[NerSearch] Loading NER model...");
        NerONNXInference ner = new NerONNXInference();
        System.out.printf(" %,d ms%n", System.currentTimeMillis() - t0);

        System.out.print("[NerSearch] Loading KAE encoder...");
        KaeEncoder kae = new KaeEncoder();

        System.out.print("[NerSearch] Loading BART model...");
        t0 = System.currentTimeMillis();
        BartONNXInference bart = new BartONNXInference();
        System.out.printf(" %,d ms%n", System.currentTimeMillis() - t0);

        // Load T-index and item details only (no extra models)
        System.out.print("[NerSearch] Loading search index...");
        t0 = System.currentTimeMillis();
        RetrievalService index = new RetrievalService(true);
        System.out.printf(" %,d ms%n", System.currentTimeMillis() - t0);

        System.out.printf("%n  Ready.%n%n");

        int bartK = Integer.parseInt(System.getenv().getOrDefault("BART_K", "1"));
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            String query = scanner.nextLine().trim();
            if (query.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(query) || "quit".equalsIgnoreCase(query)) break;

            try {
                long start = System.currentTimeMillis();

                // Step 1: NER → entities
                long t1 = System.nanoTime();
                List<NerEntity> entities = ner.extract(query);
                List<String> attrs = entities.stream()
                        .map(e -> e.type() + ":" + e.span())
                        .collect(Collectors.toList());

                // Step 2: KAE → query SID
                String querySid = kae.encode(attrs);
                long t2 = System.nanoTime();

                // Step 3: BART route → item SIDs
                List<String> itemSids = bart.routeQuerySidToItemSids(querySid, bartK);

                // Step 4: T-index lookup + prefix fallback
                var sidToItemIds = index.getSidToItemIds();
                var itemById = index.getItemById();
                Set<Long> seen = new HashSet<>();
                List<Map<String, Object>> items = new ArrayList<>();
                String method;

                // Exact match
                for (String isid : itemSids) {
                    List<Long> ids = sidToItemIds.get(isid);
                    if (ids == null) continue;
                    for (long id : ids) {
                        if (seen.add(id)) {
                            Map<String, Object> item = itemById.get(id);
                            if (item != null) items.add(item);
                        }
                    }
                }
                if (!items.isEmpty()) {
                    method = "bart_exact";
                } else {
                    // Prefix fallback
                    int[] qIdx = KaeEncoder.parseSid(querySid);
                    for (int prefixLen = 6; prefixLen >= 1 && items.isEmpty(); prefixLen--) {
                        for (var entry : sidToItemIds.entrySet()) {
                            int[] iIdx = KaeEncoder.parseSid(entry.getKey());
                            boolean match = true;
                            for (int i = 0; i < prefixLen; i++) {
                                if (qIdx[i] != 0 && iIdx[i] != qIdx[i]) { match = false; break; }
                            }
                            if (!match) continue;
                            for (long id : entry.getValue()) {
                                if (seen.add(id)) {
                                    Map<String, Object> item = itemById.get(id);
                                    if (item != null) items.add(item);
                                }
                            }
                        }
                    }
                    method = items.isEmpty() ? "none" : "prefix_match";
                }

                long elapsed = System.currentTimeMillis() - start;

                System.out.printf("  NER: %s%n", attrs);
                System.out.printf("  querySID: %s  (fill=%d)%n", querySid, countNonZero(querySid));
                System.out.printf("  [%d result%s, %dms, NER=%.0fµs, %s]%n",
                        items.size(), items.size() == 1 ? "" : "s", elapsed,
                        (t2 - t1) / 1000.0, method);

                int maxShow = Math.min(items.size(), 10);
                for (int i = 0; i < maxShow; i++) {
                    Map<String, Object> item = items.get(i);
                    String brand = Objects.toString(item.get("brand_name"), "");
                    if ("无品牌".equals(brand) || "其他/other".equals(brand)) brand = "";
                    String title = (String) item.get("item_title");
                    String cat = (String) item.get("category_level1_name");
                    System.out.printf("  %2d. %s%s | %s%n",
                            i + 1,
                            brand.isEmpty() ? "" : "[" + brand + "] ",
                            title.length() > 55 ? title.substring(0, 55) + "…" : title,
                            cat);
                }
                if (items.size() > maxShow) {
                    System.out.printf("  ... and %d more%n", items.size() - maxShow);
                }
                System.out.println();

            } catch (Exception e) {
                System.err.println("  ERROR: " + e.getMessage());
                e.printStackTrace();
            }
        }

        ner.close();
        bart.close();
        System.out.println("Bye!");
    }

    private static int countNonZero(String sid) {
        if (sid == null) return 0;
        int[] idx = KaeEncoder.parseSid(sid);
        int n = 0;
        for (int v : idx) if (v != 0) n++;
        return n;
    }
}
