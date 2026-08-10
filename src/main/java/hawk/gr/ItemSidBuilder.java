package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Batch processor: reads item data, runs Aho-Corasick dictionary encoding,
 * and builds the SID-to-item lookup index T (plus an item-to-SID forward mapping).
 *
 * <pre>
 *   items_lite JSONL → DictEncoder (AC + codebook) → item SID
 *       → items_with_sid.json        (id, title, brand, seller, category, itemSid)
 *       → sid_to_items.json          (itemSID → [itemId, ...])
 * </pre>
 *
 * Usage: {@code mvn exec:java -Dexec.mainClass="hawk.gr.ItemSidBuilder"}
 * Set ITEM_COUNT env to override sample size (default 50000).
 */
public class ItemSidBuilder {

    private static final Path ITEMS_FILE = Paths.get("/opt/py_proj/KuaiSearch/hf_data/items_lite/train.jsonl");
    private static final Path OUTPUT_DIR = Paths.get("src/main/resources");
    private static final Path ITEMS_WITH_SID = OUTPUT_DIR.resolve("items_with_sid.json");
    private static final Path SID_TO_ITEMS = OUTPUT_DIR.resolve("sid_to_items.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        int itemCount = Integer.parseInt(System.getenv().getOrDefault("ITEM_COUNT", "50000"));

        System.out.println("=".repeat(60));
        System.out.println("  Item SID Builder — Aho-Corasick Dictionary Encoding");
        System.out.println("=".repeat(60));
        System.out.printf("  Items to process: %,d%n", itemCount);
        System.out.printf("  Input:  %s%n", ITEMS_FILE);
        System.out.printf("  Output: %s%n", ITEMS_WITH_SID);
        System.out.printf("          %s%n", SID_TO_ITEMS);

        // ---- Load DictEncoder (AC automaton) ----
        System.out.println("\n[1] Loading DictEncoder (Aho-Corasick + codebook)...");
        long t0 = System.currentTimeMillis();
        DictEncoder encoder = new DictEncoder();
        System.out.printf("    %,d patterns, built in %,d ms%n",
                encoder.patternCount(), System.currentTimeMillis() - t0);

        // ---- Process items ----
        System.out.printf("%n[2] Processing %,d items...%n", itemCount);
        t0 = System.currentTimeMillis();

        List<Map<String, Object>> itemsWithSid = new ArrayList<>();
        Map<String, List<Long>> sidToItems = new LinkedHashMap<>();

        int processed = 0;
        int sidGenerated = 0;
        int allZero = 0;

        try (BufferedReader br = Files.newBufferedReader(ITEMS_FILE, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null && processed < itemCount) {
                Map<String, Object> item = GSON.fromJson(line,
                        new TypeToken<Map<String, Object>>() {}.getType());

                long itemId = ((Number) item.get("item_id")).longValue();
                String title = (String) item.get("item_title");
                String brandName = (String) item.get("brand_name");
                String sellerName = (String) item.get("seller_name");
                String cat1 = (String) item.get("category_level1_name");
                String cat2 = (String) item.get("category_level2_name");
                String cat3 = (String) item.get("category_level3_name");

                // AC dictionary encoding
                String itemSid = encoder.encode(title);
                if (itemSid != null) {
                    sidGenerated++;
                    if ("<a_0><b_0><c_0><d_0><e_0><f_0>".equals(itemSid)) {
                        allZero++;
                    }
                }

                // Build output record
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("item_id", itemId);
                record.put("item_title", title);
                record.put("brand_name", brandName);
                record.put("seller_name", sellerName);
                record.put("category_level1_name", cat1);
                record.put("category_level2_name", cat2);
                record.put("category_level3_name", cat3);
                record.put("item_sid", itemSid);
                itemsWithSid.add(record);

                // Update SID → items index
                if (itemSid != null) {
                    sidToItems.computeIfAbsent(itemSid, k -> new ArrayList<>()).add(itemId);
                }

                processed++;

                // Progress
                if (processed % 5000 == 0) {
                    long elapsed = System.currentTimeMillis() - t0;
                    double rate = processed * 1000.0 / elapsed;
                    System.out.printf("    %,d / %,d  (%.1f items/s, SID: %.1f%%, all-zero: %.1f%%)%n",
                            processed, itemCount, rate,
                            100.0 * sidGenerated / processed,
                            100.0 * allZero / processed);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("%n    Done! %,d items in %,d ms (%.1f items/s)%n",
                processed, elapsed, processed * 1000.0 / elapsed);
        System.out.printf("    SID generated: %d (%.1f%%)%n",
                sidGenerated, 100.0 * sidGenerated / processed);
        System.out.printf("    All-zero SID:  %d (%.1f%%)%n",
                allZero, 100.0 * allZero / processed);

        // ---- Save results ----
        System.out.println("\n[3] Saving results...");

        try (Writer w = Files.newBufferedWriter(ITEMS_WITH_SID, StandardCharsets.UTF_8)) {
            GSON.toJson(itemsWithSid, w);
        }
        System.out.printf("    items_with_sid.json: %,d records → %s%n",
                itemsWithSid.size(), ITEMS_WITH_SID);

        Map<String, Object> indexWrapper = new LinkedHashMap<>();
        indexWrapper.put("description", "SID-to-item lookup index T (DictEncoder). One SID maps to multiple item IDs.");
        indexWrapper.put("sid_count", sidToItems.size());
        indexWrapper.put("index", sidToItems);

        try (Writer w = Files.newBufferedWriter(SID_TO_ITEMS, StandardCharsets.UTF_8)) {
            GSON.toJson(indexWrapper, w);
        }
        System.out.printf("    sid_to_items.json: %,d unique SIDs → %s%n",
                sidToItems.size(), SID_TO_ITEMS);

        // Stats summary
        System.out.println("\n[4] Summary");
        System.out.printf("    Total items processed: %,d%n", processed);
        System.out.printf("    Unique SIDs:          %,d%n", sidToItems.size());
        System.out.printf("    Avg items per SID:     %.1f%n",
                sidToItems.isEmpty() ? 0.0 : (double) processed / sidToItems.size());

        // Sample output
        System.out.println("\n[5] Sample results (first 5):");
        for (int i = 0; i < Math.min(5, itemsWithSid.size()); i++) {
            Map<String, Object> r = itemsWithSid.get(i);
            System.out.printf("    id=%-8d sid=%-45s title=%s%n",
                    ((Number) r.get("item_id")).longValue(),
                    r.get("item_sid"),
                    r.get("item_title"));
        }

        System.out.println("\nDone!");
    }
}
