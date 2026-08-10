package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compare NER-based encoding vs Aho-Corasick dictionary-based encoding
 * on 500 items from the existing items_with_sid.json.
 */
public class EncoderComparison {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  Encoder Comparison: NER vs Aho-Corasick Dictionary");
        System.out.println("=".repeat(70));

        // 1. Load 500 items
        System.out.println("\n[1] Loading items...");
        List<Map<String, Object>> allItems = GSON.fromJson(
                Files.newBufferedReader(Paths.get("src/main/resources/items_with_sid.json")),
                new TypeToken<List<Map<String, Object>>>() {}.getType());
        List<Map<String, Object>> items = allItems.subList(0, Math.min(500, allItems.size()));
        System.out.printf("    Loaded %,d items%n", items.size());

        // 2. Load NER model (GPU)
        System.out.println("\n[2] Loading NER model...");
        long t0 = System.currentTimeMillis();
        NerONNXInference ner = new NerONNXInference();
        System.out.printf("    NER loaded in %,d ms%n", System.currentTimeMillis() - t0);

        // 3. Load KAE encoder (for NER pipeline)
        System.out.println("[3] Loading KAE encoder...");
        KaeEncoder kae = new KaeEncoder();

        // 4. Load DictEncoder (AC automaton)
        System.out.println("[4] Loading DictEncoder (Aho-Corasick)...");
        t0 = System.currentTimeMillis();
        DictEncoder dictEncoder = new DictEncoder();
        long acBuildMs = System.currentTimeMillis() - t0;
        System.out.printf("    %,d patterns, built in %,d ms%n",
                dictEncoder.patternCount(), acBuildMs);

        // 5. Run comparison
        System.out.printf("%n[5] Comparing on %,d items...%n", items.size());
        t0 = System.currentTimeMillis();

        long nerTotalNs = 0;
        long acRawTotalNs = 0;
        long acFiltTotalNs = 0;
        int nerFillCount = 0;
        int acRawFillCount = 0;
        int acFiltFillCount = 0;
        // Count matches to show filtering effect
        long acRawMatchTotal = 0;
        long acFiltMatchTotal = 0;

        List<String> diffExamples = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String title = (String) item.get("item_title");
            String nerSid = null;

            // --- NER pipeline ---
            long nerStart = System.nanoTime();
            try {
                List<NerEntity> entities = ner.extract(title);
                List<String> attrs = entities.stream()
                        .map(e -> e.type() + ":" + e.span())
                        .collect(Collectors.toList());
                nerSid = kae.encode(attrs);
            } catch (Exception e) { /* NER failed */ }
            long nerNs = System.nanoTime() - nerStart;
            nerTotalNs += nerNs;

            // --- AC pipeline (raw, no filter) ---
            long acStart = System.nanoTime();
            int[] acRawIdx = dictEncoder.encodeToIndices(title, false);
            long acNs = System.nanoTime() - acStart;
            acRawTotalNs += acNs;
            String acRawSid = KaeEncoder.formatSid(acRawIdx);

            // --- AC pipeline (longest-match filter) ---
            long acFStart = System.nanoTime();
            int[] acFiltIdx = dictEncoder.encodeToIndices(title, true);
            long acFNs = System.nanoTime() - acFStart;
            acFiltTotalNs += acFNs;
            String acFiltSid = KaeEncoder.formatSid(acFiltIdx);

            // Count matches
            acRawMatchTotal += dictEncoder.getMatcher().search(title).size();
            acFiltMatchTotal += DictEncoder.filterLongestPerPosition(
                    dictEncoder.getMatcher().search(title)).size();

            // --- Compare ---
            int nerFill = nerSid == null ? 0 : countNonZero(nerSid);
            int acRawFill = countNonZero(acRawSid);
            int acFiltFill = countNonZero(acFiltSid);

            nerFillCount += nerFill;
            acRawFillCount += acRawFill;
            acFiltFillCount += acFiltFill;

            // Collect examples
            if (diffExamples.size() < 15 && acFiltFill > nerFill) {
                diffExamples.add(String.format(
                        "title=%s%n  NER(%d):   %s%n  AC-raw(%d): %s%n  AC-filt(%d): %s",
                        title.length() > 55 ? title.substring(0, 55) + "..." : title,
                        nerFill, nerSid, acRawFill, acRawSid, acFiltFill, acFiltSid));
            }
        }

        long elapsed = System.currentTimeMillis() - t0;

        // 6. Report
        System.out.printf("%n%n%s%n", "=".repeat(70));
        System.out.println("  RESULTS");
        System.out.println("=".repeat(70));

        System.out.printf("%n--- Fill Rate ---%n");
        double avgNer = (double) nerFillCount / items.size();
        double avgAcRaw = (double) acRawFillCount / items.size();
        double avgAcFilt = (double) acFiltFillCount / items.size();
        System.out.printf("  NER:       %.2f / 6%n", avgNer);
        System.out.printf("  AC raw:    %.2f / 6  (无过滤，噪声多)%n", avgAcRaw);
        System.out.printf("  AC filtered: %.2f / 6  (最长匹配优先)%n", avgAcFilt);

        System.out.printf("%n--- Match Count (avg per item) ---%n");
        System.out.printf("  AC raw:      %.0f matches%n", (double) acRawMatchTotal / items.size());
        System.out.printf("  AC filtered: %.0f matches  (过滤掉了 %.0f%% 噪声)%n",
                (double) acFiltMatchTotal / items.size(),
                100.0 * (1.0 - (double) acFiltMatchTotal / Math.max(1, acRawMatchTotal)));

        System.out.printf("%n--- Speed ---%n");
        double nerMs = nerTotalNs / 1_000_000.0 / items.size();
        double acRawMs = acRawTotalNs / 1_000_000.0 / items.size();
        double acFiltMs = acFiltTotalNs / 1_000_000.0 / items.size();
        System.out.printf("  NER:         %.3f ms / item%n", nerMs);
        System.out.printf("  AC raw:      %.4f ms / item%n", acRawMs);
        System.out.printf("  AC filtered: %.4f ms / item  (%.0fx faster than NER)%n",
                acFiltMs, nerMs / Math.max(0.001, acFiltMs));

        System.out.printf("%n--- Examples (AC-filtered vs NER vs AC-raw) ---%n");
        for (String ex : diffExamples) {
            System.out.printf("  %s%n%n", ex);
        }

        ner.close();
        System.out.println("Done!");
    }

    private static int countNonZero(String sid) {
        if (sid == null) return 0;
        int[] indices = KaeEncoder.parseSid(sid);
        int count = 0;
        for (int v : indices) if (v != 0) count++;
        return count;
    }

    private static boolean isAllZero(String sid) {
        if (sid == null) return true;
        int[] indices = KaeEncoder.parseSid(sid);
        for (int v : indices) if (v != 0) return false;
        return true;
    }
}
