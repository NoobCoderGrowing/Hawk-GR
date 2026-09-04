package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Build the FULL-SCALE SID index from data/items_ner_attributes_with_sid.jsonl
 * (6.6M items). Each item's title is re-encoded with the CURRENT DictEncoder
 * (non-overlap tiling) so item SIDs and query SIDs share one rule.
 *
 * <pre>
 *   data/items_ner_attributes_with_sid.jsonl ──每行──► DictEncoder.encode(title)
 *        │                                          │
 *        ├──► items_with_sid.json   (JSONL: {item_id, item_title, item_sid})  ← 流式写
 *        └──► sid_to_items.json     (compact {description, sid_count, index})  ← 内存聚合并写
 * </pre>
 *
 * Item details keep ONLY id/title/sid (brand/seller/category dropped per user decision),
 * keeping startup heap ~2-3GB for 6.6M items.
 *
 * Usage: {@code java -Xmx6g -cp target/classes:$(cat /tmp/cp.txt) hawk.gr.BuildFullIndex}
 */
public class BuildFullIndex {

    private static final Path INPUT = Paths.get("data/items_ner_attributes_with_sid.jsonl");
    private static final Path OUTPUT_DIR = Paths.get("src/main/resources");
    private static final Path ITEMS_OUT = OUTPUT_DIR.resolve("items_with_sid.json");
    private static final Path INDEX_OUT = OUTPUT_DIR.resolve("sid_to_items.json");

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String ALL_ZERO_SID = "<a_0><b_0><c_0><d_0><e_0><f_0><g_0><h_0>";

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("  BuildFullIndex — 6.6M items → T-index (current rule)");
        System.out.println("=".repeat(60));

        // ---- 1. Encoder ----
        System.out.println("\n[1] Loading DictEncoder (current AC + non-overlap tiling)...");
        long t0 = System.currentTimeMillis();
        DictEncoder encoder = new DictEncoder();
        System.out.printf("    %,d patterns in %,d ms%n", encoder.patternCount(),
                System.currentTimeMillis() - t0);

        // ---- 2. Back up current (50k) files ----
        System.out.println("\n[2] Backing up current index files...");
        if (Files.exists(ITEMS_OUT)) {
            Path b = ITEMS_OUT.resolveSibling("items_with_sid.json.50k.bak");
            Files.copy(ITEMS_OUT, b, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("    " + b);
        }
        if (Files.exists(INDEX_OUT)) {
            Path b = INDEX_OUT.resolveSibling("sid_to_items.json.50k.bak");
            Files.copy(INDEX_OUT, b, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("    " + b);
        }

        // ---- 3. Stream encode + write ----
        System.out.printf("%n[3] Streaming %s → %s ...%n", INPUT, ITEMS_OUT);
        Map<String, List<Long>> sidToItems = new HashMap<>(2 << 20);
        long processed = 0, allZero = 0, blankTitle = 0;
        t0 = System.currentTimeMillis();
        try (BufferedReader br = Files.newBufferedReader(INPUT, StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(ITEMS_OUT, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                long id = o.has("item_id") && !o.get("item_id").isJsonNull()
                        ? o.get("item_id").getAsLong() : -1;
                String title = o.has("item_title") && !o.get("item_title").isJsonNull()
                        ? o.get("item_title").getAsString() : "";
                if (title.isBlank()) blankTitle++;
                String sid = encoder.encode(title);
                if (ALL_ZERO_SID.equals(sid)) allZero++;

                JsonObject rec = new JsonObject();
                rec.addProperty("item_id", id);
                rec.addProperty("item_title", title);
                rec.addProperty("item_sid", sid);
                w.write(GSON.toJson(rec));
                w.newLine();

                sidToItems.computeIfAbsent(sid, k -> new ArrayList<>()).add(id);
                processed++;
                if (processed % 500_000 == 0) {
                    long el = System.currentTimeMillis() - t0;
                    System.out.printf("    %,d / 6,634,118  (%.1f/s, unique SIDs: %,d)%n",
                            processed, processed * 1000.0 / el, sidToItems.size());
                }
            }
        }
        long el = System.currentTimeMillis() - t0;
        System.out.printf("    Done: %,d items in %d s (%.1f/s). unique SIDs=%,d, all-zero=%,d, blankTitle=%,d%n",
                processed, el / 1000, processed * 1000.0 / el, sidToItems.size(), allZero, blankTitle);

        // ---- 4. Write T index ----
        System.out.println("\n[4] Writing " + INDEX_OUT + " ...");
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("description",
                "SID-to-item lookup index T (DictEncoder, full-scale). One SID maps to multiple item IDs.");
        wrapper.put("sid_count", sidToItems.size());
        wrapper.put("index", sidToItems);
        byte[] json = GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8);
        Files.write(INDEX_OUT, json);
        System.out.printf("    %,d unique SIDs, %,d bytes%n", sidToItems.size(), json.length);

        // ---- 5. Stats ----
        System.out.println("\n[5] Summary");
        System.out.printf("    Items:         %,d%n", processed);
        System.out.printf("    Unique SIDs:   %,d%n", sidToItems.size());
        System.out.printf("    Avg/SID:       %.1f%n",
                sidToItems.isEmpty() ? 0.0 : (double) processed / sidToItems.size());

        System.out.println("\nDone!");
    }
}
