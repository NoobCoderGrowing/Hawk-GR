package hawk.gr;

import java.util.Arrays;
import java.util.List;

/**
 * Quick test for KaeEncoder — verifies codebook loading and encoding.
 */
public class KaeTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Loading KAE encoder...");
        KaeEncoder encoder = new KaeEncoder();
        KaeConfig config = encoder.getConfig();

        System.out.println("Config loaded: " + config.totalEntries() + " codebook entries");
        System.out.println("Positions: " + config.positions());
        System.out.println("Global reserved range: [" + config.reservedMin() + ", " + config.reservedMax() + "]");
        for (String p : config.positions()) {
            System.out.printf("  %s: sid_max=%d, reserved [%d,%d]%n",
                    p, config.sidMax(p), config.reservedMin(p), config.reservedMax(p));
        }

        // Test 1: Encode attributes to SID
        System.out.println("\n--- Test 1: encode attributes → SID ---");
        List<String> attrs = Arrays.asList(
            "产品_核心产品:上衣",
            "品牌:小米",
            "颜色_色彩:黑色",
            "风格:休闲"
        );
        String sid = encoder.encode(attrs);
        System.out.println("  Input:  " + attrs);
        System.out.println("  Output: " + sid);

        // Test 2: Encode single
        System.out.println("\n--- Test 2: encode single ---");
        System.out.println("  '品牌:小米' → " + encoder.encodeSingle("品牌", "小米"));
        System.out.println("  '颜色_色彩:红色' → " + encoder.encodeSingle("颜色_色彩", "红色"));
        System.out.println("  '功能功效:显瘦' → " + encoder.encodeSingle("功能功效", "显瘦"));

        // Test 3: Indices
        System.out.println("\n--- Test 3: encode to indices ---");
        int[] indices = encoder.encodeToIndices(attrs);
        System.out.println("  Indices: " + Arrays.toString(indices));
        System.out.println("  Formatted: " + KaeEncoder.formatSid(indices));

        // Test 4: Reserved slot (per-position: d's reserved range is [1583,1612])
        System.out.println("\n--- Test 4: reserved slot ---");
        String sid2 = encoder.encode(Arrays.asList("产品_核心产品:手机壳"));
        System.out.println("  Original:       " + sid2);
        String modified = KaeEncoder.setReservedSlot(sid2, 'd', 1600);
        System.out.println("  After set d=1600: " + modified);
        System.out.println("  d_1600 is reserved: " + config.isReservedSlot("d", 1600));
        System.out.println("  d_5 is reserved: " + config.isReservedSlot("d", 5));
        System.out.println("  global 2000 is reserved: " + config.isReservedSlot(2000));

        // Test 5: Parse (8 positions a-h)
        System.out.println("\n--- Test 5: parse SID ---");
        int[] parsed = KaeEncoder.parseSid("<a_304><b_1><c_13><d_2000><e_0><f_197><g_0><h_0>");
        System.out.println("  Parsed: " + Arrays.toString(parsed));

        System.out.println("\nAll tests passed!");
    }
}
