package hawk.gr.kae;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * KAE (Keyword-Aligned Encoding) configuration.
 * <p>
 * Loads the ECOM6 group mapping, position assignment, and codebook
 * (attribute value → discrete index) from resource files exported
 * from the Python training pipeline.
 */
public class KaeConfig {

    private static final String BASE = "kae/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Group → list of NER prefixes
    private final Map<String, List<String>> groupToPrefixes;
    // NER prefix → group
    private final Map<String, String> prefixToGroup;
    // Group → codebook position (a-h)
    private final Map<String, String> groupToPosition;
    // Position (a-h) → codebook (attribute value → index)
    private final Map<String, Map<String, Integer>> codebooks;
    // Position order
    private final List<String> positions;

    private final int emptySlot;
    private final int normalMin, normalMax;
    private final int reservedMin, reservedMax;
    // Per-position core SID ceiling (a: 2189, b: 1330, ...) — reserved range for a
    // position is (sidMax, sidMax + reservedPerPos]; positions share a global
    // normal/reserved range for backward compatibility (max sidMax governs it).
    private final Map<String, Integer> sidMax;
    private final int reservedPerPos;

    public KaeConfig() throws IOException {
        // Load main config
        Map<String, Object> config = loadJson(BASE + "kae_config.json",
                new TypeReference<Map<String, Object>>() {});
        this.groupToPosition = readStringMap(config, "group_to_position");
        this.prefixToGroup = readStringMap(config, "ner_prefix_to_group");
        this.positions = (List<String>) config.get("positions");
        this.emptySlot = (int) ((List<Integer>) config.get("normal_range")).get(0) - 1;
        this.normalMin = (int) ((List<Integer>) config.get("normal_range")).get(0);
        this.normalMax = (int) ((List<Integer>) config.get("normal_range")).get(1);
        this.reservedMin = (int) ((List<Integer>) config.get("reserved_range")).get(0);
        this.reservedMax = (int) ((List<Integer>) config.get("reserved_range")).get(1);
        this.sidMax = readIntMap(config, "sid_max");
        this.reservedPerPos = (int) config.get("reserved_per_pos");

        // Build reverse mapping: group → prefixes
        this.groupToPrefixes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : prefixToGroup.entrySet()) {
            groupToPrefixes.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        // Load codebooks for each position (noise entries filtered out — see
        // cleanCodebook: single ASCII letters/digits and pure-symbol strings
        // are dropped so they can't pollute SIDs).
        this.codebooks = new LinkedHashMap<>();
        for (String pos : positions) {
            Map<String, Integer> cb = loadJson(
                    BASE + "codebook_" + pos + ".json",
                    new TypeReference<Map<String, Integer>>() {});
            codebooks.put(pos, cleanCodebook(cb));
        }
    }

    // ---- accessors ----

    /** Map a NER prefix (e.g. "品牌") to its ECOM6 group (e.g. "人群代言"). */
    public String nerPrefixToGroup(String nerPrefix) {
        return prefixToGroup.getOrDefault(nerPrefix, null);
    }

    /** Map a group to its codebook position (a-h). */
    public String groupToPosition(String group) {
        return groupToPosition.get(group);
    }

    /** Look up the discrete index for an attribute value within a position's codebook. */
    public Integer lookupIndex(String pos, String attrValue) {
        Map<String, Integer> cb = codebooks.get(pos);
        return cb != null ? cb.get(attrValue) : null;
    }

    /** The noise-filtered codebook for a position (a-h). */
    public Map<String, Integer> codebook(String pos) {
        return codebooks.get(pos);
    }

    /**
     * Drop noise entries from a raw codebook so they can't pollute SIDs:
     * <ul>
     *   <li>single ASCII letters/digits — e.g. {@code "t"→647} in 材质款式 matched
     *       the bare "t" inside "T恤" and produced a bogus e_647;</li>
     *   <li>pure symbol/emoji strings with no letters or digits — e.g. "🐠", "￥",
     *       "++++" — never a meaningful attribute.</li>
     * </ul>
     * Single CJK characters (白/黑) and multi-char words (13 pro, v8) are kept.
     */
    static Map<String, Integer> cleanCodebook(Map<String, Integer> cb) {
        Map<String, Integer> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : cb.entrySet()) {
            if (!isNoiseEntry(e.getKey())) {
                clean.put(e.getKey(), e.getValue());
            }
        }
        return clean;
    }

    private static boolean isNoiseEntry(String key) {
        if (key == null || key.isEmpty()) return true;
        // Single ASCII letter or digit
        if (key.length() == 1) {
            char c = key.charAt(0);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                return true;
            }
        }
        // Pure symbols/emoji — no letters and no digits anywhere
        for (int i = 0; i < key.length(); i++) {
            if (Character.isLetterOrDigit(key.charAt(i))) return false;
        }
        return true;
    }

    /** The CANONICAL position order (a-h). */
    public List<String> positions() { return positions; }

    public int emptySlot() { return emptySlot; }
    public int normalMin() { return normalMin; }
    public int normalMax() { return normalMax; }
    public int reservedMin() { return reservedMin; }
    public int reservedMax() { return reservedMax; }

    /** Core (non-reserved) SID ceiling for one position, e.g. "a" → 2189. */
    public int sidMax(String pos) { return sidMax.getOrDefault(pos, normalMax); }
    /** First reserved slot for a position: sidMax(pos)+1. */
    public int reservedMin(String pos) { return sidMax(pos) + 1; }
    /** Last reserved slot for a position: sidMax(pos)+reservedPerPos. */
    public int reservedMax(String pos) { return sidMax(pos) + reservedPerPos; }
    /** Number of reserved slots per position. */
    public int reservedPerPos() { return reservedPerPos; }

    /** True if idx falls in the global reserved range (derived from the max sidMax). */
    public boolean isReservedSlot(int idx) {
        return idx >= reservedMin && idx <= reservedMax;
    }

    /** True if idx falls in position {@code pos}'s reserved range. */
    public boolean isReservedSlot(String pos, int idx) {
        return idx >= reservedMin(pos) && idx <= reservedMax(pos);
    }

    /** Format a SID token string: {@code <pos_idx>} */
    public static String formatToken(String pos, int idx) {
        return "<" + pos + "_" + idx + ">";
    }

    /** Number of codebook entries across all positions. */
    /** Get the full group → position mapping (e.g. "产品核心" → "a"). */
    public Map<String, String> getGroupToPosition() {
        return Collections.unmodifiableMap(groupToPosition);
    }

    public int totalEntries() {
        return codebooks.values().stream().mapToInt(Map::size).sum();
    }

    // ---- internal ----

    @SuppressWarnings("unchecked")
    private static Map<String, String> readStringMap(Map<String, Object> config, String key) {
        return (Map<String, String>) config.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> readIntMap(Map<String, Object> config, String key) {
        return (Map<String, Integer>) config.get(key);
    }

    private static <T> T loadJson(String resourcePath, TypeReference<T> typeRef) throws IOException {
        try (InputStream in = KaeConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return MAPPER.readValue(in, typeRef);
        }
    }
}
