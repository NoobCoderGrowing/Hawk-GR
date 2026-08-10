package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Batch processor: reads item data, runs NER + KAE encoding, and builds
 * the SID-to-item lookup index T (plus an item-to-SID forward mapping).
 *
 * <pre>
 *   items_lite JSONL → NER extract → KAE encode → item SID
 *       → items_with_sid.json        (id, title, brand, seller, category, itemSid)
 *       → sid_to_items.json          (itemSID → [itemId, ...])
 * </pre>
 *
 * Usage: {@code mvn exec:java -Dexec.mainClass="hawk.gr.ItemSidBuilder"}
 * Set ITEM_COUNT env to override sample size (default 100000).
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
        System.out.println("  Item SID Builder — Batch NER + KAE Encoding");
        System.out.println("=".repeat(60));
        System.out.printf("  Items to process: %,d%n", itemCount);
        System.out.printf("  Input:  %s%n", ITEMS_FILE);
        System.out.printf("  Output: %s%n", ITEMS_WITH_SID);
        System.out.printf("          %s%n", SID_TO_ITEMS);

        // ---- Load models ----
        System.out.println("\n[1] Loading NER model...");
        long t0 = System.currentTimeMillis();
        NerONNXInference ner = new NerONNXInference();
        System.out.printf("    NER loaded in %,d ms%n", System.currentTimeMillis() - t0);

        System.out.println("[2] Loading KAE encoder...");
        KaeEncoder kae = new KaeEncoder();
        System.out.printf("    Codebook entries: %,d%n", kae.getConfig().totalEntries());

        // ---- Process items ----
        System.out.printf("%n[3] Processing %,d items...%n", itemCount);
        t0 = System.currentTimeMillis();

        List<Map<String, Object>> itemsWithSid = new ArrayList<>();
        Map<String, List<Long>> sidToItems = new LinkedHashMap<>();

        int processed = 0;
        int nerSuccess = 0;
        int sidSuccess = 0;

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

                // Run NER on title
                String itemSid = null;
                try {
                    List<NerEntity> entities = ner.extract(title);
                    if (!entities.isEmpty()) {
                        nerSuccess++;
                        List<String> attrs = entities.stream()
                            .map(e -> e.type() + ":" + e.span())
                            .collect(Collectors.toList());
                        itemSid = kae.encode(attrs);
                        if (itemSid != null) {
                            sidSuccess++;
                        }
                    }
                } catch (Exception e) {
                    // NER or KAE failed for this item — skip
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
                    System.out.printf("    %,d / %,d  (%.1f items/s, NER: %.1f%%, SID: %.1f%%)%n",
                            processed, itemCount, rate,
                            100.0 * nerSuccess / processed,
                            100.0 * sidSuccess / processed);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("%n    Done! %,d items in %,d ms (%.1f items/s)%n",
                processed, elapsed, processed * 1000.0 / elapsed);
        System.out.printf("    NER extracted: %d (%.1f%%)%n",
                nerSuccess, 100.0 * nerSuccess / processed);
        System.out.printf("    SID generated: %d (%.1f%%)%n",
                sidSuccess, 100.0 * sidSuccess / processed);

        // ---- Save results ----
        System.out.println("\n[4] Saving results...");

        // Items with SID
        try (Writer w = Files.newBufferedWriter(ITEMS_WITH_SID, StandardCharsets.UTF_8)) {
            GSON.toJson(itemsWithSid, w);
        }
        System.out.printf("    items_with_sid.json: %,d records → %s%n",
                itemsWithSid.size(), ITEMS_WITH_SID);

        // SID → items index (compact: just the mapping, no pretty-print for size)
        Map<String, Object> indexWrapper = new LinkedHashMap<>();
        indexWrapper.put("description", "SID-to-item lookup index T. One SID maps to multiple item IDs.");
        indexWrapper.put("sid_count", sidToItems.size());
        indexWrapper.put("index", sidToItems);

        try (Writer w = Files.newBufferedWriter(SID_TO_ITEMS, StandardCharsets.UTF_8)) {
            GSON.toJson(indexWrapper, w);
        }
        System.out.printf("    sid_to_items.json: %,d unique SIDs → %s%n",
                sidToItems.size(), SID_TO_ITEMS);

        // Stats summary
        System.out.println("\n[5] Summary");
        System.out.printf("    Total items processed: %,d%n", processed);
        System.out.printf("    Unique SIDs:          %,d%n", sidToItems.size());
        System.out.printf("    Avg items per SID:     %.1f%n",
                sidToItems.isEmpty() ? 0.0 : (double) processed / sidToItems.size());
        System.out.printf("    Empty SID (no NER):   %,d (%.1f%%)%n",
                processed - sidSuccess, 100.0 * (processed - sidSuccess) / processed);

        // Sample output
        System.out.println("\n[6] Sample results (first 5):");
        for (int i = 0; i < Math.min(5, itemsWithSid.size()); i++) {
            Map<String, Object> r = itemsWithSid.get(i);
            System.out.printf("    id=%-8d sid=%-45s title=%s%n",
                    ((Number) r.get("item_id")).longValue(),
                    r.get("item_sid"),
                    r.get("item_title"));
        }

        ner.close();
        System.out.println("\nDone!");
    }
}
