package hawk.gr;

import java.util.*;

/**
 * Interactive CLI: read query → return matching products.
 *
 * <pre>
 *   $ ./run.sh hawk.gr.HawkSearch
 *   > 白色跑步鞋
 *   [3 results]
 *   1. 达芙妮平底单鞋女软底瓢鞋... | 女鞋
 *   2. 护士鞋女白色平底一脚蹬... | 女鞋
 *   3. ...
 *   > exit
 * </pre>
 */
public class HawkSearch {

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════╗");
        System.out.println("  ║     Hawk-GR 商品检索                     ║");
        System.out.println("  ║     Query → SID → Items                  ║");
        System.out.println("  ╚══════════════════════════════════════════╝");
        System.out.println();

        // Load service
        long t0 = System.currentTimeMillis();
        RetrievalService service = new RetrievalService();
        System.out.printf("  Ready. (%,d ms)%n%n", System.currentTimeMillis() - t0);

        int bartK = Integer.parseInt(System.getenv().getOrDefault("BART_K", "1"));

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String query = scanner.nextLine().trim();
            if (query.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(query) || "quit".equalsIgnoreCase(query)) break;

            try {
                long start = System.currentTimeMillis();
                Map<String, Object> result = service.searchDebug(query, bartK);
                long elapsed = System.currentTimeMillis() - start;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
                int found = items.size();

                System.out.printf("  [%d result%s, %dms, %s]%n",
                        found, found == 1 ? "" : "s", elapsed, result.get("match_method"));

                int maxShow = Math.min(found, 10);
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
                if (found > maxShow) {
                    System.out.printf("  ... and %d more%n", found - maxShow);
                }
                System.out.println();

            } catch (Exception e) {
                System.err.println("  ERROR: " + e.getMessage());
            }
        }

        service.close();
        System.out.println("Bye!");
    }
}
