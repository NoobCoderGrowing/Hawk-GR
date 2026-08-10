package hawk.gr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Dictionary-based encoder using Aho-Corasick matching.
 * <p>
 * Replaces the NER+KAE pipeline with direct dictionary lookup:
 * <pre>
 *   text → AC scan codebook vocabulary → matched attribute words
 *        → group by position → pick smallest index → 6-token SID
 * </pre>
 *
 * This is the encoding method described in the OneRetrieval paper (§3.4.3):
 * items and queries are both encoded by deterministic dictionary matching
 * against the production attribute vocabulary.
 *
 * <h3>Usage</h3>
 * <pre>
 *   DictEncoder encoder = new DictEncoder();   // loads codebook → AC automaton
 *   String sid = encoder.encode("小米黑色手机壳");
 *   // → "<a_304><b_1><c_13><d_0><e_0><f_5>"
 * </pre>
 */
public class DictEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KaeConfig config;
    private final AhoCorasickMatcher matcher;
    private final List<String> positions;

    public DictEncoder() throws IOException {
        this.config = new KaeConfig();
        this.positions = config.positions();
        this.matcher = new AhoCorasickMatcher();

        // Register all codebook entries as AC patterns
        // The 6 ECOM6 groups (from paper §3.3) — loaded from kae_config.json
        // We iterate over positions and find the group for each
        for (String pos : positions) {
            Map<String, Integer> codebook = loadCodebookForPosition(pos);
            for (Map.Entry<String, Integer> entry : codebook.entrySet()) {
                matcher.add(entry.getKey(), pos, entry.getValue());
            }
        }
        matcher.build();
    }

    /**
     * Encode text to a 6-token SID string via dictionary matching.
     */
    public String encode(String text) {
        int[] indices = encodeToIndices(text);
        return KaeEncoder.formatSid(indices);
    }

    /**
     * Encode text to raw 6-int indices.
     */
    public int[] encodeToIndices(String text) {
        return encodeToIndices(text, true);
    }

    /**
     * Encode text to raw 6-int indices, with optional longest-match filtering.
     */
    public int[] encodeToIndices(String text, boolean filterLongest) {
        List<AhoCorasickMatcher.Match> matches = matcher.search(text);

        if (filterLongest) {
            // Cross-position: same span → keep only lowest-index position
            matches = disambiguateCrossPosition(matches);
            // Intra-position: longer match subsumes shorter substring
            matches = filterLongestPerPosition(matches);
        }

        // Group matches by position
        Map<String, List<Integer>> posToIndices = new LinkedHashMap<>();
        for (String pos : positions) {
            posToIndices.put(pos, new ArrayList<>());
        }

        for (AhoCorasickMatcher.Match m : matches) {
            posToIndices.get(m.position).add(m.index);
        }

        // Pick smallest index per position (most frequent word)
        int[] indices = new int[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            String pos = positions.get(i);
            List<Integer> vals = posToIndices.get(pos);
            if (vals.isEmpty()) {
                indices[i] = config.emptySlot();
            } else {
                indices[i] = vals.stream().mapToInt(Integer::intValue).min().getAsInt();
            }
        }
        return indices;
    }

    /**
     * Cross-position disambiguation: when the same text span matches multiple
     * positions, keep only the position with the lowest codebook index.
     * <p>
     * E.g. "小" appears at [0,1) as e_1 and a_1921 → keep only e_1 because
     * index 1 < 1921, meaning "小" is most authentically a material word.
     */
    static List<AhoCorasickMatcher.Match> disambiguateCrossPosition(
            List<AhoCorasickMatcher.Match> matches) {
        // Group matches by text span
        Map<String, List<AhoCorasickMatcher.Match>> bySpan = new LinkedHashMap<>();
        for (AhoCorasickMatcher.Match m : matches) {
            String key = m.start + ":" + m.end;
            bySpan.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }

        List<AhoCorasickMatcher.Match> result = new ArrayList<>();
        for (Map.Entry<String, List<AhoCorasickMatcher.Match>> entry : bySpan.entrySet()) {
            List<AhoCorasickMatcher.Match> spanMatches = entry.getValue();
            if (spanMatches.size() == 1) {
                result.add(spanMatches.get(0));
            } else {
                // Multiple positions claim the same span — keep lowest index
                AhoCorasickMatcher.Match best = spanMatches.get(0);
                for (int i = 1; i < spanMatches.size(); i++) {
                    if (spanMatches.get(i).index < best.index) {
                        best = spanMatches.get(i);
                    }
                }
                result.add(best);
            }
        }
        return result;
    }

    /**
     * Within each position, keep only the longest match when matches overlap.
     * A short substring like "套" is discarded when "枕套" covers it.
     * Non-overlapping long matches are all retained.
     */
    static List<AhoCorasickMatcher.Match> filterLongestPerPosition(List<AhoCorasickMatcher.Match> matches) {
        // Group by position
        Map<String, List<AhoCorasickMatcher.Match>> byPos = new LinkedHashMap<>();
        for (AhoCorasickMatcher.Match m : matches) {
            byPos.computeIfAbsent(m.position, k -> new ArrayList<>()).add(m);
        }

        List<AhoCorasickMatcher.Match> filtered = new ArrayList<>();

        for (Map.Entry<String, List<AhoCorasickMatcher.Match>> entry : byPos.entrySet()) {
            List<AhoCorasickMatcher.Match> posMatches = entry.getValue();

            // Sort by length descending, then by start position
            posMatches.sort((a, b) -> {
                int lenCmp = Integer.compare(
                        b.end - b.start, a.end - a.start);  // longer first
                if (lenCmp != 0) return lenCmp;
                return Integer.compare(a.start, b.start);
            });

            // Interval coverage: accept longest, mark range as covered
            List<int[]> covered = new ArrayList<>();
            for (AhoCorasickMatcher.Match m : posMatches) {
                if (!isFullyCovered(m.start, m.end, covered)) {
                    filtered.add(m);
                    covered.add(new int[]{m.start, m.end});
                }
            }
        }

        return filtered;
    }

    /** Check if [start, end) is fully contained within any covered interval. */
    private static boolean isFullyCovered(int start, int end, List<int[]> covered) {
        for (int[] cov : covered) {
            if (cov[0] <= start && end <= cov[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all matched attribute words from text, grouped by position.
     */
    public Map<String, List<String>> extractAttributes(String text) {
        List<AhoCorasickMatcher.Match> matches = matcher.search(text);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String pos : positions) {
            result.put(pos, new ArrayList<>());
        }
        // Deduplicate words per position
        for (AhoCorasickMatcher.Match m : matches) {
            List<String> words = result.get(m.position);
            if (!words.contains(m.word)) {
                words.add(m.word);
            }
        }
        return result;
    }

    public int patternCount() { return matcher.patternCount(); }
    public AhoCorasickMatcher getMatcher() { return matcher; }
    public KaeConfig getConfig() { return config; }

    private Map<String, Integer> loadCodebookForPosition(String pos) throws IOException {
        // Reverse-lookup group from position
        String group = null;
        for (Map.Entry<String, String> e : config.getGroupToPosition().entrySet()) {
            if (e.getValue().equals(pos)) { group = e.getKey(); break; }
        }
        if (group == null) return Collections.emptyMap();
        return loadCodebook(group);
    }

    private Map<String, Integer> loadCodebook(String group) throws IOException {
        String path = "kae/codebook_" + group + ".json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) return Collections.emptyMap();
            return MAPPER.readValue(in, new TypeReference<Map<String, Integer>>() {});
        }
    }
}
