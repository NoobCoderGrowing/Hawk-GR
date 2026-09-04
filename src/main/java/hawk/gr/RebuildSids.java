package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Rebuild the SID index T after an encoder-rule change, without the original
 * train.jsonl (which no longer exists on this machine).
 * <p>
 * The surviving items_with_sid.json already holds every item's title, so we
 * re-run the CURRENT DictEncoder over those titles and rewrite BOTH index files
 * with the new rule — keeping query SID and item SID encoding consistent.
 * <p>
 * Originals are backed up to *.before.bak before overwriting.
 *
 * Usage: {@code mvn exec:java -Dexec.mainClass="hawk.gr.RebuildSids"}
 */
public class RebuildSids {

    private static final Path OUTPUT_DIR = Paths.get("src/main/resources");
    private static final Path ITEMS_WITH_SID = OUTPUT_DIR.resolve("items_with_sid.json");
    private static final Path SID_TO_ITEMS = OUTPUT_DIR.resolve("sid_to_items.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("  RebuildSids — re-encode item SIDs with current rule");
        System.out.println("=".repeat(60));

        // ---- 1. Load encoder (CURRENT resolution rule) ----
        System.out.println("\n[1] Loading DictEncoder (current AC + non-overlap tiling)...");
        long t0 = System.currentTimeMillis();
        DictEncoder encoder = new DictEncoder();
        System.out.printf("    %,d patterns, built in %,d ms%n",
                encoder.patternCount(), System.currentTimeMillis() - t0);

        // ---- 2. Read existing item records (titles preserved) ----
        System.out.println("\n[2] Reading " + ITEMS_WITH_SID + " ...");
        List<Map<String, Object>> items = GSON.fromJson(
                Files.newBufferedReader(ITEMS_WITH_SID),
                new TypeToken<List<Map<String, Object>>>() {}.getType());
        System.out.printf("    %,d item records%n", items.size());

        // ---- 3. Re-encode ----
        System.out.println("\n[3] Re-encoding item titles...");
        Map<String, List<Long>> sidToItems = new LinkedHashMap<>();
        int changed = 0;
        int allZero = 0;
        t0 = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> rec = items.get(i);
            long itemId = ((Number) rec.get("item_id")).longValue();
            String title = (String) rec.get("item_title");
            String oldSid = (String) rec.get("item_sid");
            String newSid = encoder.encode(title);
            if (!Objects.equals(oldSid, newSid)) changed++;
            if ("<a_0><b_0><c_0><d_0><e_0><f_0>".equals(newSid)) allZero++;
            rec.put("item_sid", newSid);
            if (newSid != null) {
                sidToItems.computeIfAbsent(newSid, k -> new ArrayList<>()).add(itemId);
            }
            if ((i + 1) % 10000 == 0) {
                System.out.printf("    %,d / %,d%n", i + 1, items.size());
            }
        }
        System.out.printf("    Done in %,d ms. %,d / %,d titles changed SID; %,d all-zero.%n",
                System.currentTimeMillis() - t0, changed, items.size(), allZero);

        // ---- 4. Back up originals ----
        System.out.println("\n[4] Backing up originals...");
        Path bakItems = ITEMS_WITH_SID.resolveSibling("items_with_sid.json.before.bak");
        Path bakSids = SID_TO_ITEMS.resolveSibling("sid_to_items.json.before.bak");
        Files.copy(ITEMS_WITH_SID, bakItems, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(SID_TO_ITEMS, bakSids, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("    " + bakItems);
        System.out.println("    " + bakSids);

        // ---- 5. Write both files ----
        System.out.println("\n[5] Writing " + ITEMS_WITH_SID + " ...");
        try (Writer w = Files.newBufferedWriter(ITEMS_WITH_SID, StandardCharsets.UTF_8)) {
            GSON.toJson(items, w);
        }
        System.out.println("    ok");

        Map<String, Object> indexWrapper = new LinkedHashMap<>();
        indexWrapper.put("description", "SID-to-item lookup index T (DictEncoder). One SID maps to multiple item IDs.");
        indexWrapper.put("sid_count", sidToItems.size());
        indexWrapper.put("index", sidToItems);

        System.out.println("    Writing " + SID_TO_ITEMS + " ...");
        try (Writer w = Files.newBufferedWriter(SID_TO_ITEMS, StandardCharsets.UTF_8)) {
            GSON.toJson(indexWrapper, w);
        }
        System.out.printf("    %,d unique SIDs%n", sidToItems.size());

        System.out.println("\nDone!");
    }
}
