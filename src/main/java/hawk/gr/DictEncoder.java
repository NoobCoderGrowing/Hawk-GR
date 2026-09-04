package hawk.gr;

import hawk.gr.ac.AhoCorasickMatcher;
import hawk.gr.kae.KaeConfig;
import hawk.gr.kae.KaeEncoder;

import java.io.IOException;
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

    private final KaeConfig config;
    private final AhoCorasickMatcher matcher;
    private final List<String> positions;

    public DictEncoder() throws IOException {
        this.config = new KaeConfig();
        this.positions = config.positions();
        this.matcher = new AhoCorasickMatcher();

        // Register all codebook entries as AC patterns
        // The 6 ECOM6 groups (from paper §3.3) — loaded from kae_config.json
        // Codebooks come pre-filtered from KaeConfig (noise entries like single
        // ASCII letters/digits and pure-symbol strings are dropped).
        for (String pos : positions) {
            Map<String, Integer> codebook = config.codebook(pos);
            if (codebook == null) continue;
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
        // Lowercase user input only (codebook patterns are left untouched), so
        // case variants like "T恤"/"t恤" resolve to the same codebook entry.
        List<AhoCorasickMatcher.Match> matches = matcher.search(text.toLowerCase(Locale.ROOT));

        if (filterLongest) {
            // Unified resolution: one string → one category (a-priority, else
            // smallest index); then non-overlapping greedy, longest-first, so
            // survivors tile the text without overlap.
            matches = resolveMatches(matches);
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
     * Resolve raw AC matches into the final set that contributes to the SID.
     * <p>
     * Rules:
     * <ol>
     *   <li><b>One string → one category.</b> Each matched span (word occurrence)
     *       is resolved to exactly one position: position "a" has priority;
     *       otherwise the smallest codebook index wins.</li>
     *   <li><b>Non-overlapping greedy, longest-first.</b> Spans are accepted in
     *       decreasing length; a span is dropped only if it overlaps an already
     *       accepted span, so survivors tile the text without sharing characters
     *       (e.g. "网球拍" → "网球"+"拍", not the overlapping "网球"+"球拍").</li>
     * </ol>
     */
    static List<AhoCorasickMatcher.Match> resolveMatches(List<AhoCorasickMatcher.Match> matches) {
        // Step 1: each distinct span → a single category
        Map<String, List<AhoCorasickMatcher.Match>> bySpan = new LinkedHashMap<>();
        for (AhoCorasickMatcher.Match m : matches) {
            String key = m.start + ":" + m.end;
            bySpan.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        List<AhoCorasickMatcher.Match> resolved = new ArrayList<>();
        for (List<AhoCorasickMatcher.Match> spanMatches : bySpan.values()) {
            resolved.add(pickCategory(spanMatches));
        }

        // Step 2: non-overlapping greedy — longest first; drop a span only when
        // it shares a character with an already-accepted span, so survivors tile
        // the text without overlap ("网球拍" → "网球"+"拍").
        resolved.sort((a, b) -> {
            int lenCmp = Integer.compare(b.end - b.start, a.end - a.start); // longer first
            if (lenCmp != 0) return lenCmp;
            // tie: position "a" first, then smaller index
            int aPri = "a".equals(a.position) ? 0 : 1;
            int bPri = "a".equals(b.position) ? 0 : 1;
            if (aPri != bPri) return Integer.compare(aPri, bPri);
            return Integer.compare(a.index, b.index);
        });

        List<AhoCorasickMatcher.Match> result = new ArrayList<>();
        List<int[]> selected = new ArrayList<>();
        for (AhoCorasickMatcher.Match m : resolved) {
            if (!overlapsAny(m.start, m.end, selected)) {
                result.add(m);
                selected.add(new int[]{m.start, m.end});
            }
        }
        return result;
    }

    /** Pick the single category for one span: position "a" first, else smallest index. */
    private static AhoCorasickMatcher.Match pickCategory(List<AhoCorasickMatcher.Match> spanMatches) {
        for (AhoCorasickMatcher.Match m : spanMatches) {
            if ("a".equals(m.position)) return m;   // a-priority
        }
        AhoCorasickMatcher.Match best = spanMatches.get(0);
        for (int i = 1; i < spanMatches.size(); i++) {
            if (spanMatches.get(i).index < best.index) best = spanMatches.get(i);
        }
        return best;
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
     * Check if [start, end) shares at least one character position with any
     * already-selected interval. Adjacent intervals ([0,2) and [2,3)) do NOT
     * overlap — they tile the text.
     */
    private static boolean overlapsAny(int start, int end, List<int[]> intervals) {
        for (int[] iv : intervals) {
            if (start < iv[1] && iv[0] < end) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the attribute words that actually make it into the SID, grouped by
     * position. Mirrors encodeToIndices() selection logic exactly: lowercase
     * input → cross-position disambiguation → longest-match filtering →
     * keep the match(es) with the minimum index per position.
     */
    public Map<String, List<String>> extractSelectedAttributes(String text) {
        List<AhoCorasickMatcher.Match> matches = matcher.search(text.toLowerCase(Locale.ROOT));
        matches = resolveMatches(matches);

        // Group surviving matches by position
        Map<String, List<AhoCorasickMatcher.Match>> byPos = new LinkedHashMap<>();
        for (String pos : positions) byPos.put(pos, new ArrayList<>());
        for (AhoCorasickMatcher.Match m : matches) byPos.get(m.position).add(m);

        // Per position, keep the words tied at the minimum index (same pick
        // as encodeToIndices, which uses min().getAsInt()).
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String pos : positions) result.put(pos, new ArrayList<>());
        for (String pos : positions) {
            List<AhoCorasickMatcher.Match> posMatches = byPos.get(pos);
            if (posMatches.isEmpty()) continue;
            int minIdx = Integer.MAX_VALUE;
            for (AhoCorasickMatcher.Match m : posMatches) {
                minIdx = Math.min(minIdx, m.index);
            }
            for (AhoCorasickMatcher.Match m : posMatches) {
                if (m.index == minIdx && !result.get(pos).contains(m.word)) {
                    result.get(pos).add(m.word);
                }
            }
        }
        return result;
    }

    /**
     * Get all matched attribute words from text, grouped by position.
     */
    public Map<String, List<String>> extractAttributes(String text) {
        List<AhoCorasickMatcher.Match> matches = matcher.search(text.toLowerCase(Locale.ROOT));
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
}
