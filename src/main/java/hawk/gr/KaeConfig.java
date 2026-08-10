package hawk.gr;

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

    // ECOM6 group → list of NER prefixes
    private final Map<String, List<String>> groupToPrefixes;
    // NER prefix → ECOM6 group
    private final Map<String, String> prefixToGroup;
    // ECOM6 group → codebook position (a-f)
    private final Map<String, String> groupToPosition;
    // Position (a-f) → codebook (attribute value → index)
    private final Map<String, Map<String, Integer>> codebooks;
    // Position order
    private final List<String> positions;

    private final int emptySlot;
    private final int normalMin, normalMax;
    private final int reservedMin, reservedMax;

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

        // Build reverse mapping: group → prefixes
        this.groupToPrefixes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : prefixToGroup.entrySet()) {
            groupToPrefixes.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        // Load codebooks for each group
        this.codebooks = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : groupToPosition.entrySet()) {
            String group = e.getKey();
            String pos = e.getValue();
            Map<String, Integer> cb = loadJson(
                    BASE + "codebook_" + group + ".json",
                    new TypeReference<Map<String, Integer>>() {});
            codebooks.put(pos, cb);
        }
    }

    // ---- accessors ----

    /** Map a NER prefix (e.g. "品牌") to its ECOM6 group (e.g. "人群代言"). */
    public String nerPrefixToGroup(String nerPrefix) {
        return prefixToGroup.getOrDefault(nerPrefix, null);
    }

    /** Map an ECOM6 group to its codebook position (a-f). */
    public String groupToPosition(String group) {
        return groupToPosition.get(group);
    }

    /** Look up the discrete index for an attribute value within a position's codebook. */
    public Integer lookupIndex(String pos, String attrValue) {
        Map<String, Integer> cb = codebooks.get(pos);
        return cb != null ? cb.get(attrValue) : null;
    }

    /** The CANONICAL position order (a-f). */
    public List<String> positions() { return positions; }

    public int emptySlot() { return emptySlot; }
    public int normalMin() { return normalMin; }
    public int normalMax() { return normalMax; }
    public int reservedMin() { return reservedMin; }
    public int reservedMax() { return reservedMax; }

    public boolean isReservedSlot(int idx) {
        return idx >= reservedMin && idx <= reservedMax;
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

    private static <T> T loadJson(String resourcePath, TypeReference<T> typeRef) throws IOException {
        try (InputStream in = KaeConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return MAPPER.readValue(in, typeRef);
        }
    }
}
