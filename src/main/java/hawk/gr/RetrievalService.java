package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Full retrieval pipeline: query → querySID → itemSID → items.
 *
 * <pre>
 *   "白色跑步鞋"
 *       ↓ DictEncoder.encode()
 *   querySID: "<a_579><b_0><c_534><d_0><e_0><f_0>"
 *       ↓ BartONNXInference.routeQuerySidToItemSids(k)
 *   itemSIDs: ["<a_579><b_204><c_0>...", ...]
 *       ↓ T lookup (sid_to_items.json)
 *   items: [{item_id, item_title, brand_name, ...}, ...]
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   RetrievalService service = new RetrievalService();
 *   List{@code <Map<String,Object>>} results = service.search("白色跑步鞋", 10);
 * </pre>
 */
public class RetrievalService implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final DictEncoder dictEncoder;
    private final BartONNXInference bart;
    private final Map<String, List<Long>> sidToItemIds;  // T: itemSID → [itemId, ...]
    private final Map<Long, Map<String, Object>> itemById;  // itemId → item record

    public RetrievalService() throws Exception {
        // 1. DictEncoder (pure CPU, no GPU needed)
        System.out.print("[RetrievalService] Loading DictEncoder...");
        long t0 = System.currentTimeMillis();
        this.dictEncoder = new DictEncoder();
        System.out.printf(" %,d patterns, %,d ms%n",
                dictEncoder.patternCount(), System.currentTimeMillis() - t0);

        // 2. BART model (GPU if available)
        System.out.print("[RetrievalService] Loading BART model...");
        t0 = System.currentTimeMillis();
        this.bart = new BartONNXInference();
        System.out.printf(" %,d ms%n", System.currentTimeMillis() - t0);

        // 3. Load T: SID → item IDs lookup index
        System.out.print("[RetrievalService] Loading SID→item index...");
        t0 = System.currentTimeMillis();
        this.sidToItemIds = loadSidToItemIds();
        System.out.printf(" %,d SIDs, %,d ms%n",
                sidToItemIds.size(), System.currentTimeMillis() - t0);

        // 4. Load item details
        System.out.print("[RetrievalService] Loading item details...");
        t0 = System.currentTimeMillis();
        this.itemById = loadItemDetails();
        System.out.printf(" %,d items, %,d ms%n",
                itemById.size(), System.currentTimeMillis() - t0);
    }

    /**
     * Search: raw query → top-K items.
     *
     * @param query free-form Chinese query, e.g. "白色跑步鞋"
     * @param k     number of item SID candidates (beam size)
     * @return list of item records, each with id, title, brand, seller, category, sid
     */
    public List<Map<String, Object>> search(String query, int k) throws Exception {
        // Step 1: Query → query SID
        String querySid = dictEncoder.encode(query);

        // Step 2: Query SID → item SIDs (beam search)
        List<String> itemSids = bart.routeQuerySidToItemSids(querySid, k);

        // Step 3: Item SIDs → actual items via T lookup
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();

        for (String itemSid : itemSids) {
            List<Long> itemIds = sidToItemIds.get(itemSid);
            if (itemIds == null) continue;
            for (long itemId : itemIds) {
                if (seen.add(itemId)) {
                    Map<String, Object> item = itemById.get(itemId);
                    if (item != null) results.add(item);
                }
            }
        }

        // Step 4: Fallback — query SID prefix match against T-index
        if (results.isEmpty()) {
            results = prefixSearch(querySid, seen, new ArrayList<>());
        }

        return results;
    }

    /**
     * Convenience: search with detailed debug info.
     */
    public Map<String, Object> searchDebug(String query, int k) throws Exception {
        long t0 = System.nanoTime();

        // Step 1: query → query SID
        String querySid = dictEncoder.encode(query);
        Map<String, List<String>> attrs = dictEncoder.extractAttributes(query);

        // Step 2: BART beam search → item SID candidates
        long t1 = System.nanoTime();
        List<String> bartItemSids = bart.routeQuerySidToItemSids(querySid, k);
        long t2 = System.nanoTime();

        // Step 3: Resolve items
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> usedSids = new ArrayList<>();
        String matchMethod = "none";

        // 3a: Exact match via BART-generated item SIDs
        for (String itemSid : bartItemSids) {
            List<Long> itemIds = sidToItemIds.get(itemSid);
            if (itemIds != null) {
                for (long itemId : itemIds) {
                    if (seen.add(itemId)) {
                        Map<String, Object> item = itemById.get(itemId);
                        if (item != null) { items.add(item); usedSids.add(itemSid); }
                    }
                }
            }
        }
        if (!items.isEmpty()) matchMethod = "bart_exact";

        // 3b: Fallback — prefix match query SID directly against T-index
        if (items.isEmpty()) {
            List<Map<String, Object>> prefixItems = prefixSearch(querySid, seen, usedSids);
            if (!prefixItems.isEmpty()) {
                items.addAll(prefixItems);
                matchMethod = "prefix_match";
            }
        }
        long t3 = System.nanoTime();

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("query", query);
        debug.put("query_sid", querySid);
        debug.put("query_attrs", attrs);
        debug.put("query_fill", countNonZero(querySid));
        debug.put("bart_candidates", bartItemSids.size());
        debug.put("match_method", matchMethod);
        debug.put("used_sids", usedSids.size() > 3 ? usedSids.subList(0, 3) : usedSids);
        debug.put("items_found", items.size());
        debug.put("time_encode_us", (t1 - t0) / 1000.0);
        debug.put("time_route_us", (t2 - t1) / 1000.0);
        debug.put("time_lookup_us", (t3 - t2) / 1000.0);
        debug.put("items", items);
        return debug;
    }

    /**
     * Progressive prefix search: use query SID to match item SIDs in T-index.
     * Starts with full SID, progressively drops trailing positions until hits found.
     * This is the key advantage of KAE — queries and items share the same semantic space.
     */
    private List<Map<String, Object>> prefixSearch(String querySid, Set<Long> seen, List<String> usedSids) {
        int[] qIdx = KaeEncoder.parseSid(querySid);
        List<Map<String, Object>> results = new ArrayList<>();

        // Start from full SID, progressively relax
        for (int prefixLen = 6; prefixLen >= 1; prefixLen--) {
            // Build prefix pattern: match first N positions, rest wildcard
            for (Map.Entry<String, List<Long>> entry : sidToItemIds.entrySet()) {
                String itemSid = entry.getKey();
                int[] itemIdx = KaeEncoder.parseSid(itemSid);

                // Check if first prefixLen positions match (non-zero positions must match)
                boolean match = true;
                for (int i = 0; i < prefixLen; i++) {
                    if (qIdx[i] != 0 && itemIdx[i] != qIdx[i]) {
                        match = false;
                        break;
                    }
                }
                if (!match) continue;

                for (long itemId : entry.getValue()) {
                    if (seen.add(itemId)) {
                        Map<String, Object> item = itemById.get(itemId);
                        if (item != null) {
                            results.add(item);
                            usedSids.add(itemSid);
                        }
                    }
                }
            }

            if (!results.isEmpty()) break;  // stop at tightest match
        }
        return results;
    }

    private int countNonZero(String sid) {
        if (sid == null) return 0;
        int[] idx = KaeEncoder.parseSid(sid);
        int n = 0;
        for (int v : idx) if (v != 0) n++;
        return n;
    }

    // ---- Load index files ----

    @SuppressWarnings("unchecked")
    private Map<String, List<Long>> loadSidToItemIds() throws IOException {
        Path path = Paths.get("src/main/resources/sid_to_items.json");
        Map<String, Object> wrapper = GSON.fromJson(
                Files.newBufferedReader(path),
                new TypeToken<Map<String, Object>>() {}.getType());
        Map<String, Object> index = (Map<String, Object>) wrapper.get("index");

        Map<String, List<Long>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : index.entrySet()) {
            List<Double> raw = (List<Double>) e.getValue();
            List<Long> ids = new ArrayList<>();
            for (Double d : raw) ids.add(d.longValue());
            result.put(e.getKey(), ids);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> loadItemDetails() throws IOException {
        Path path = Paths.get("src/main/resources/items_with_sid.json");
        List<Map<String, Object>> items = GSON.fromJson(
                Files.newBufferedReader(path),
                new TypeToken<List<Map<String, Object>>>() {}.getType());

        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            long id = ((Number) item.get("item_id")).longValue();
            result.put(id, item);
        }
        return result;
    }

    @Override
    public void close() throws Exception {
        if (bart != null) bart.close();
    }

    // ---- Demo ----
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(65));
        System.out.println("  RetrievalService Demo");
        System.out.println("=".repeat(65));

        RetrievalService service = new RetrievalService();

        String[] queries = {
            "小米黑色手机壳",
            "冬季加厚羽绒服男款",
            "女士显瘦牛仔裤",
            "白色跑步鞋",
            "不锈钢保温杯",
        };

        int k = 1;  // BART beam size

        for (String query : queries) {
            System.out.printf("%n%s%n", "-".repeat(65));
            Map<String, Object> debug = service.searchDebug(query, k);

            System.out.printf("Query:        %s%n", debug.get("query"));
            System.out.printf("Query SID:    %s  (fill=%d)%n",
                    debug.get("query_sid"), debug.get("query_fill"));
            System.out.printf("Method:       %s%n", debug.get("match_method"));
            System.out.printf("BART candidates: %s%n", debug.get("bart_candidates"));
            System.out.printf("Items found:  %d%n", debug.get("items_found"));
            System.out.printf("Time: encode=%.0fµs  route=%.0fµs  lookup=%.0fµs%n",
                    (double) debug.get("time_encode_us"),
                    (double) debug.get("time_route_us"),
                    (double) debug.get("time_lookup_us"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) debug.get("items");
            System.out.println("Top results:");
            for (int i = 0; i < Math.min(5, items.size()); i++) {
                Map<String, Object> item = items.get(i);
                System.out.printf("  %d. [%s] %s | %s | %s%n",
                        i + 1,
                        item.get("brand_name"),
                        item.get("item_title"),
                        item.get("category_level1_name"),
                        item.get("item_sid"));
            }
        }

        service.close();
        System.out.printf("%n%s%nDone!%n", "=".repeat(65));
    }
}
