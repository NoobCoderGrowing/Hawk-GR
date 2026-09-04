package hawk.gr;

import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

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

        // JLine line reader — provides arrow-key cursor editing even when
        // stdin is not a real TTY (degrades to a simple readLine on pipes).
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        while (true) {
            String query;
            try {
                query = reader.readLine("> ").trim();
            } catch (UserInterruptException e) {
                continue;   // Ctrl-C: discard current line, keep looping
            } catch (EndOfFileException e) {
                break;      // Ctrl-D: exit
            }
            if (query.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(query) || "quit".equalsIgnoreCase(query)) break;

            try {
                long start = System.currentTimeMillis();
                Map<String, Object> result = service.searchDebug(query, bartK);
                long elapsed = System.currentTimeMillis() - start;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
                int found = items.size();

                // Show query SID and matched attributes
                String querySid = (String) result.get("query_sid");
                int fill = (int) result.get("query_fill");
                @SuppressWarnings("unchecked")
                Map<String, List<String>> attrs = (Map<String, List<String>>) result.get("query_attrs");

                System.out.printf("  querySID: %s  (fill=%d)%n", querySid, fill);
                for (String pos : new String[]{"a","b","c","d","e","f"}) {
                    List<String> words = attrs.getOrDefault(pos, List.of());
                    if (!words.isEmpty()) {
                        String[] names = {"产品核心","人群代言","风格样式","功能功效","材质款式","时空场景"};
                        int pi = pos.charAt(0) - 'a';
                        System.out.printf("    %s(%s): %s%n", pos, names[pi], String.join(", ", words));
                    }
                }
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
        terminal.close();
        System.out.println("Bye!");
    }
}
