package hawk.gr;

import java.io.IOException;
import java.util.*;

/**
 * KAE (Keyword-Aligned Encoding) encoder.
 * <p>
 * Converts NER-extracted item/query attributes into a 6-digit Semantic ID (SID)
 * using a discrete codebook built during the OneRetrieval preprocessing pipeline.
 *
 * <h3>Pipeline</h3>
 * <pre>
 *   attributes (list of "prefix:value" strings)
 *       ↓  ECOM6 grouping
 *   group → list of values
 *       ↓  codebook lookup
 *   6-tuple SID indices
 *       ↓  format
 *   "<a_304><b_1><c_13><d_0><e_0><f_197>"
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   KaeEncoder encoder = new KaeEncoder();          // loads config once
 *   String sid = encoder.encode(attributes);          // → SID string
 *   int[] indices = encoder.encodeToIndices(attrs);   // → [304, 1, 13, 0, 0, 197]
 * </pre>
 */
public class KaeEncoder {

    private final KaeConfig config;

    public KaeEncoder() throws IOException {
        this.config = new KaeConfig();
    }

    public KaeEncoder(KaeConfig config) {
        this.config = config;
    }

    /**
     * Encode a list of attributes into a SID string.
     *
     * @param nerAttributes list of "prefix:value" strings,
     *                      e.g. ["品牌:小米", "颜色:黑色", "产品:手机壳"]
     * @return SID string like "<a_23><b_1><c_1><d_0><e_0><f_5>"
     */
    public String encode(List<String> nerAttributes) {
        int[] indices = encodeToIndices(nerAttributes);
        return formatSid(indices);
    }

    /**
     * Encode attributes to raw indices (position order a-f).
     * Unassigned positions are filled with the empty-slot value (0).
     */
    public int[] encodeToIndices(List<String> nerAttributes) {
        // Group attributes by ECOM6 group → position
        Map<String, List<String>> posToValues = new LinkedHashMap<>();
        for (String pos : config.positions()) {
            posToValues.put(pos, new ArrayList<>());
        }

        for (String attr : nerAttributes) {
            String prefix = extractPrefix(attr);
            String value = extractValue(attr);
            if (prefix == null || value == null) continue;

            String group = config.nerPrefixToGroup(prefix);
            if (group == null) continue;  // unknown prefix, skip

            String pos = config.groupToPosition(group);
            if (pos == null) continue;

            posToValues.get(pos).add(value);
        }

        // Look up each position's index
        int[] indices = new int[config.positions().size()];
        List<String> positions = config.positions();
        for (int i = 0; i < positions.size(); i++) {
            String pos = positions.get(i);
            List<String> values = posToValues.get(pos);
            if (values.isEmpty()) {
                indices[i] = config.emptySlot();
            } else {
                indices[i] = lookupBest(pos, values);
            }
        }
        return indices;
    }

    /**
     * Encode a single attribute (prefix + value) and return its SID token.
     * Useful for building a partial SID incrementally.
     *
     * @return SID token like "<a_23>" or null if the attribute is unknown
     */
    public String encodeSingle(String prefix, String value) {
        String group = config.nerPrefixToGroup(prefix);
        if (group == null) return null;
        String pos = config.groupToPosition(group);
        if (pos == null) return null;
        Integer idx = config.lookupIndex(pos, value);
        if (idx == null) return null;
        return KaeConfig.formatToken(pos, idx);
    }

    /**
     * Set a reserved slot value at the given position.
     * Reserved slots (1987-2046) are identity-routed for personalization.
     *
     * @param pos position (a-f)
     * @param reservedValue value in reserved range [1987, 2046]
     */
    public static String setReservedSlot(String sid, char pos, int reservedValue) {
        // Parse existing SID, replace the target position with reserved value
        int[] indices = parseSid(sid);
        int posIdx = pos - 'a';
        if (posIdx >= 0 && posIdx < indices.length) {
            indices[posIdx] = reservedValue;
        }
        return formatSid(indices);
    }

    // ---- utility ----

    /** Format indices array to SID string. */
    public static String formatSid(int[] indices) {
        StringBuilder sb = new StringBuilder();
        String[] positions = {"a", "b", "c", "d", "e", "f"};
        for (int i = 0; i < indices.length && i < positions.length; i++) {
            sb.append(KaeConfig.formatToken(positions[i], indices[i]));
        }
        return sb.toString();
    }

    /** Parse a SID string to indices array. */
    public static int[] parseSid(String sid) {
        int[] indices = new int[6];
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[a-f]_(\\d+)>").matcher(sid);
        int i = 0;
        while (m.find() && i < 6) {
            indices[i++] = Integer.parseInt(m.group(1));
        }
        return indices;
    }

    public KaeConfig getConfig() { return config; }

    // ---- internal ----

    private String extractPrefix(String attribute) {
        int colon = attribute.indexOf(':');
        return colon > 0 ? attribute.substring(0, colon) : null;
    }

    private String extractValue(String attribute) {
        int colon = attribute.indexOf(':');
        return colon > 0 ? attribute.substring(colon + 1) : attribute;
    }

    /** Look up the best matching index for a position's values. Picks the smallest index
     *  (more frequent/common entities have smaller indices in the codebook). */
    private int lookupBest(String pos, List<String> values) {
        int best = Integer.MAX_VALUE;
        for (String value : values) {
            Integer idx = config.lookupIndex(pos, value);
            if (idx != null && idx < best) {
                best = idx;
            }
        }
        return best == Integer.MAX_VALUE ? config.emptySlot() : best;
    }
}
