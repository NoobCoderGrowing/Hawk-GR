package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full retrieval pipeline: query → querySID → itemSID → items.
 *
 * <pre>
 *   "白色跑步鞋"
 *       ↓ DictEncoder.encode()
 *   querySID: "<a_579><b_0><c_534><d_0><e_0><f_0>"
 *       ↓ BartONNXInference.routeQuerySidToItemSids(k)
 *   itemSIDs: ["<a_579><b_204><c_0>...", ...]
 *       ↓ T lookup (sid_to_items.json)
 *   items: [{item_id, item_title, brand_name, ...}, ...]
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   RetrievalService service = new RetrievalService();
 *   List{@code <Map<String,Object>>} results = service.search("白色跑步鞋", 10);
 * </pre>
 */
public class RetrievalService implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final DictEncoder dictEncoder;
    private final BartONNXInference bart;
    private final KaeConfig config;
    private final Map<String, List<Long>> sidToItemIds;  // T: itemSID → [itemId, ...]
    private final Map<Long, Map<String, Object>> itemById;  // itemId → item record
    /** itemSID → int[8] 解析缓存（通配查找用；运行时 publish 的新 SID 懒解析加入）。 */
    private final Map<String, int[]> sidParsed = new ConcurrentHashMap<>();

    private static final List<String> POS_ORDER = List.of("a", "b", "c", "d", "e", "f", "g", "h");
    /** 返回给前端的结果条数上限：全量索引下单个 SID 可能挂上万商品，必须截断。 */
    private static final int MAX_RESULTS = 500;

    // ---- reserved-slot bypass (OneRetrieval §3.2 / §3.4.4) ----
    // 新词(小写) -> {posIndex, reservedSlot} — 运营可控的确定性绑定（P3）
    private final Map<String, int[]> reservedWords = new ConcurrentHashMap<>();
    // "pos:slot"（如 "b:2047"）-> 绑定的商品 id 集合（论文 T 表绑定，P2）
    private final Map<String, List<Long>> reservedSlotItems = new ConcurrentHashMap<>();
    // 已占用的 "pos:slot"
    private final Set<String> usedReservedSlots = ConcurrentHashMap.newKeySet();

    // ---- 坑位系统（keyword → rank → item_id）：运营在关键词下固定商品排序位 ----
    private final Map<String, Map<Integer, Long>> keywordSlots = new ConcurrentHashMap<>();

    /** Full constructor: load DictEncoder + BART + T-index + items. */
    public RetrievalService() throws Exception {
        this(false);
    }

    /** Lightweight constructor: load only the T-index and item details (no models). */
    public RetrievalService(boolean indexOnly) throws Exception {
        if (indexOnly) {
            this.dictEncoder = null;
            this.bart = null;
            this.config = null;
        } else {
            System.out.print("[RetrievalService] Loading DictEncoder...");
            long t0 = System.currentTimeMillis();
            this.dictEncoder = new DictEncoder();
            this.config = dictEncoder.getConfig();
            System.out.printf(" %,d patterns, %,d ms%n",
                    dictEncoder.patternCount(), System.currentTimeMillis() - t0);

            System.out.print("[RetrievalService] Loading BART model...");
            t0 = System.currentTimeMillis();
            this.bart = new BartONNXInference();
            System.out.printf(" %,d ms%n", System.currentTimeMillis() - t0);
        }
        System.out.print("[RetrievalService] Loading SID→item index...");
        long t0 = System.currentTimeMillis();
        this.sidToItemIds = loadSidToItemIds();
        System.out.printf(" %,d SIDs, %,d ms%n", sidToItemIds.size(), System.currentTimeMillis() - t0);

        System.out.print("[RetrievalService] Loading item details...");
        t0 = System.currentTimeMillis();
        this.itemById = loadItemDetails();
        System.out.printf(" %,d items, %,d ms%n", itemById.size(), System.currentTimeMillis() - t0);

        // 通配查找用的 SID 解析缓存：一次性解析全部索引键
        System.out.print("[RetrievalService] Parsing SIDs for wildcard lookup...");
        t0 = System.currentTimeMillis();
        for (String sid : sidToItemIds.keySet()) {
            sidParsed.put(sid, KaeEncoder.parseSid(sid));
        }
        System.out.printf(" %,d SIDs, %,d ms%n", sidParsed.size(), System.currentTimeMillis() - t0);
    }

    /**
     * Search using an externally-provided query SID (e.g. from NER+KAE path).
     * Skips DictEncoder; only does BART routing + T-index lookup.
     */
    public Map<String, Object> searchWithQuerySid(String querySid, int k) throws Exception {
        int[] qIdx = KaeEncoder.parseSid(querySid);

        // 全 0 SID = 没有任何属性约束：不路由 BART、不返回结果
        if (countNonZero(qIdx) == 0) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("query_sid", querySid);
            empty.put("query_sid_hits", 0);
            empty.put("query_all_zero", true);
            empty.put("match_method", "none");
            empty.put("bart_candidates", 0);
            empty.put("items_found", 0);
            empty.put("items", List.of());
            return empty;
        }

        // 第一步：query_sid 本身也作为检索模式（0=通配），命中结果排最前
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> usedSids = new ArrayList<>();
        Map<String, Object> directR = querySidLookup(qIdx, seen);
        int querySidHits = (Integer) directR.get("total");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> direct = (List<Map<String, Object>>) directR.get("items");
        for (Map<String, Object> it : direct) { items.add(it); usedSids.add(querySid); }
        boolean directHad = !direct.isEmpty();

        // 第二步：统一走 BART（含保留槽）→ 生成 item SID；query 为 0 的位置归 0，0=通配匹配
        List<String> itemSids = bart.routeQuerySidToItemSids(querySid, k);
        Set<String> maskedSeen = new HashSet<>();
        List<String> maskedSids = new ArrayList<>();
        Map<String, Integer> maskedHits = new LinkedHashMap<>();
        boolean bartHad = false;
        for (String itemSid : itemSids) {
            if (items.size() >= MAX_RESULTS) break;
            int[] masked = maskItemSidToQuery(qIdx, KaeEncoder.parseSid(itemSid));
            String maskedSid = KaeEncoder.formatSid(masked);
            if (!maskedSeen.add(maskedSid)) continue;  // 同一归 0 后的模式只查一次
            maskedSids.add(maskedSid);
            Map<String, Object> r = wildcardLookup(masked, MAX_RESULTS, seen);
            maskedHits.put(maskedSid, (Integer) r.get("total"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> found = (List<Map<String, Object>>) r.get("items");
            for (Map<String, Object> it : found) { items.add(it); usedSids.add(maskedSid); bartHad = true; }
        }

        String method;
        if (items.isEmpty()) method = "none";
        else if (directHad && bartHad) method = "query_sid+bart";
        else if (directHad) method = "query_sid_wildcard";
        else method = "bart_exact";

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("query_sid", querySid);
        debug.put("query_sid_hits", querySidHits);   // query_sid 通配命中的商品总数
        debug.put("match_method", method);
        debug.put("bart_candidates", itemSids.size());
        debug.put("bart_sids", itemSids);
        debug.put("bart_candidate_hits", sidHitCounts(itemSids));
        debug.put("bart_masked_sids", maskedSids);
        debug.put("bart_masked_hits", maskedHits);
        debug.put("used_sids", usedSids.size() > 3 ? usedSids.subList(0, 3) : usedSids);
        debug.put("items_found", items.size());
        debug.put("items", items);
        return debug;
    }

    /**
     * Search: raw query → top-K items.
     *
     * @param query free-form Chinese query, e.g. "白色跑步鞋"
     * @param k     number of item SID candidates (beam size)
     * @return list of item records, each with id, title, brand, seller, category, sid
     */
    public List<Map<String, Object>> search(String query, int k) throws Exception {
        // Step 1: Query → query SID（含 reserved 覆写：已注入新词确定性映射到保留槽）
        int[] qIdx = encodeWithReservedOverrides(query);
        String querySid = KaeEncoder.formatSid(qIdx);

        // query_sid 全 0 = 未匹配到任何词表属性：不做 BART、不返回结果（避免全库通配噪音）
        if (countNonZero(qIdx) == 0) return List.of();

        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();

        // Step 2a: query_sid 本身也作为检索模式（0=通配），命中结果排最前 ——
        // query 自己的语义子空间一定可检索，不受 BART 生成质量影响
        {
            Map<String, Object> directR = querySidLookup(qIdx, seen);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> direct = (List<Map<String, Object>>) directR.get("items");
            results.addAll(direct);
        }

        // Step 2b: BART beam search → item SID 候选（含保留槽统一走 BART）；
        // query 为 0 的位置在候选上归 0，再以 0=通配查找（BART 不得约束未指定位置）
        List<String> itemSids = bart.routeQuerySidToItemSids(querySid, k);
        Set<String> maskedSeen = new HashSet<>();
        for (String itemSid : itemSids) {
            if (results.size() >= MAX_RESULTS) break;
            int[] masked = maskItemSidToQuery(qIdx, KaeEncoder.parseSid(itemSid));
            String maskedSid = KaeEncoder.formatSid(masked);
            if (!maskedSeen.add(maskedSid)) continue;  // 同一归 0 后的模式只查一次
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> found =
                    (List<Map<String, Object>>) wildcardLookup(masked, MAX_RESULTS, seen).get("items");
            results.addAll(found);
        }

        // Step 3: 只走 BART 路由（无 similarity fallback），命中为空则返回空

        // Step 4: 坑位重排 —— query 命中坑位关键词时，按运营设定固定商品排序位
        return applySlotRanking(query, results);
    }

    /**
     * Convenience: search with detailed debug info.
     */
    public Map<String, Object> searchDebug(String query, int k) throws Exception {
        long t0 = System.nanoTime();

        // Step 1: query → query SID（含 reserved 覆写）
        int[] qIdx = encodeWithReservedOverrides(query);
        String querySid = KaeEncoder.formatSid(qIdx);
        // attrs 与编码路径用同一输入源：命中保留词时先剔除其字符再 AC 扫描（否则
        // 子串如 "labubu" 里的 "lab" 会误报到 f 品牌组），随后补回保留词本身。
        // 这样展示的"进入 SID 的属性"与实际 query SID 对齐。
        String lowerQ = query.toLowerCase(Locale.ROOT);
        List<String> hit = hitReservedWords(lowerQ);
        String attrsSource = hit.isEmpty() ? query : lowerQ;
        for (String w : hit) attrsSource = attrsSource.replace(w, "");
        Map<String, List<String>> attrs = dictEncoder.extractSelectedAttributes(attrsSource);
        // reserved 新词 AC 自动机不认识，手动补进 attrs 便于前端展示
        enrichReservedAttrs(attrs, query);

        // query_sid 全 0 = 未匹配到任何词表属性：不路由 BART、不检索 ——
        // 否则 BART 把全 0 归 0 掩码当全库通配，返回整库噪音。querySidLookup 与归 0
        // 循环对全 0 均自然为空。
        boolean queryAllZero = countNonZero(qIdx) == 0;

        // Step 2: BART beam search → item SID candidates（含保留槽统一走 BART）
        long t1 = System.nanoTime();
        List<String> bartItemSids = queryAllZero ? List.of()
                : bart.routeQuerySidToItemSids(querySid, k);
        long t2 = System.nanoTime();

        // Step 3: Resolve items —— query_sid 通配 + 归 0 通配
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> usedSids = new ArrayList<>();
        String matchMethod = "none";

        // 3a: query_sid 本身也作为检索模式（0=通配），命中结果排最前 ——
        // query 自己的语义子空间一定可检索，不受 BART 生成质量影响
        Map<String, Object> directR = querySidLookup(qIdx, seen);
        int querySidHits = (Integer) directR.get("total");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> direct = (List<Map<String, Object>>) directR.get("items");
        String querySidPattern = KaeEncoder.formatSid(qIdx);
        for (Map<String, Object> it : direct) { items.add(it); usedSids.add(querySidPattern); }
        boolean directHad = !direct.isEmpty();

        // 3b: query 为 0 的位置在 BART 生成的 item SID 上归 0，再以 0=通配匹配（BART 不得约束未指定位置）
        Set<String> maskedSeen = new HashSet<>();
        List<String> maskedSids = new ArrayList<>();
        Map<String, Integer> maskedHits = new LinkedHashMap<>();
        boolean bartHad = false;
        for (String itemSid : bartItemSids) {
            if (items.size() >= MAX_RESULTS) break;
            int[] masked = maskItemSidToQuery(qIdx, KaeEncoder.parseSid(itemSid));
            String maskedSid = KaeEncoder.formatSid(masked);
            if (!maskedSeen.add(maskedSid)) continue;  // 同一归 0 后的模式只查一次
            maskedSids.add(maskedSid);
            Map<String, Object> r = wildcardLookup(masked, MAX_RESULTS, seen);
            maskedHits.put(maskedSid, (Integer) r.get("total"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> found = (List<Map<String, Object>>) r.get("items");
            for (Map<String, Object> it : found) { items.add(it); usedSids.add(maskedSid); bartHad = true; }
        }
        if (items.isEmpty()) matchMethod = "none";
        else if (directHad && bartHad) matchMethod = "query_sid+bart";
        else if (directHad) matchMethod = "query_sid_wildcard";
        else matchMethod = "bart_exact";

        // 3c: 只走 BART 路由（无 similarity fallback）
        long t3 = System.nanoTime();

        // Step 4: 坑位重排 —— query 命中坑位关键词时，按运营设定固定商品排序位
        items = applySlotRanking(query, items);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("query", query);
        debug.put("query_sid", querySid);
        debug.put("query_attrs", attrs);
        debug.put("query_fill", countNonZero(querySid));
        debug.put("query_all_zero", queryAllZero);   // 全 0：未匹配到任何词表属性，不返回结果
        debug.put("query_sid_hits", querySidHits);   // query_sid 通配命中的商品总数
        debug.put("reserved_override", reservedOverrideInfo(qIdx));
        debug.put("slot_override", matchSlotKeyword(query));
        debug.put("bart_candidates", bartItemSids.size());
        debug.put("bart_sids", bartItemSids);
        debug.put("bart_candidate_hits", sidHitCounts(bartItemSids));
        debug.put("bart_masked_sids", maskedSids);
        debug.put("bart_masked_hits", maskedHits);
        debug.put("match_method", matchMethod);
        debug.put("used_sids", usedSids.size() > 3 ? usedSids.subList(0, 3) : usedSids);
        debug.put("items_found", items.size());
        debug.put("time_encode_us", (t1 - t0) / 1000.0);
        debug.put("time_route_us", (t2 - t1) / 1000.0);
        debug.put("time_lookup_us", (t3 - t2) / 1000.0);
        debug.put("items", items);
        return debug;
    }

    /** 每个 beam 候选 SID → 索引中命中的商品数（0 = 索引无此 SID，BART 生成但 T 表查不到）。 */
    private Map<String, Integer> sidHitCounts(List<String> itemSids) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String sid : itemSids) {
            List<Long> ids = sidToItemIds.get(sid);
            m.put(sid, ids == null ? 0 : ids.size());
        }
        return m;
    }

    // ---- 通配查找：query 为 0 的位置在 BART 生成的 item SID 上归 0，0 = 通配 ----

    private int[] parseSidCached(String sid) {
        return sidParsed.computeIfAbsent(sid, KaeEncoder::parseSid);
    }

    /** pattern 中 0 = 通配（该位置任意值），非 0 = 必须与 item 该位置精确相等。 */
    private boolean sidMatches(int[] pattern, int[] key) {
        for (int p = 0; p < 8; p++) {
            if (pattern[p] != 0 && pattern[p] != key[p]) return false;
        }
        return true;
    }

    /**
     * query 为 0 的位置在 BART 生成的 item SID 上也归 0：BART 不得约束用户未指定的位置，
     * 只保留 query 实际指定位置的生成值。
     */
    private int[] maskItemSidToQuery(int[] qIdx, int[] itemIdx) {
        int[] masked = new int[8];
        for (int p = 0; p < 8; p++) {
            masked[p] = (qIdx[p] == 0) ? 0 : itemIdx[p];
        }
        return masked;
    }

    /**
     * 通配查找：pattern 中 0=通配。返回 {@code {items: 命中的商品(去重, 上限 limit), total: 命中总数}}。
     * 按索引中 SID 的加载顺序遍历，越靠前越先返回。
     */
    private Map<String, Object> wildcardLookup(int[] pattern, int limit, Set<Long> seen) {
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        String patternSid = KaeEncoder.formatSid(pattern);
        for (Map.Entry<String, List<Long>> e : sidToItemIds.entrySet()) {
            if (!sidMatches(pattern, parseSidCached(e.getKey()))) continue;
            for (long id : e.getValue()) {
                total++;
                if (items.size() >= limit) continue;  // 已够，只计数不再收集
                if (seen.add(id)) {
                    Map<String, Object> item = itemById.get(id);
                    if (item != null) {
                        // 复制并标注实际用于匹配的 SID（归 0 后的模式），不改共享缓存里的 item
                        Map<String, Object> copy = new LinkedHashMap<>(item);
                        copy.put("matched_sid", patternSid);
                        items.add(copy);
                    }
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("total", total);
        return r;
    }

    /**
     * query_sid 本身也作为检索模式加入（0 = 通配）：query 自己的语义子空间一定能被检索到，
     * 不受 BART 生成质量影响。返回 {@code {items, total}}；query 全 0（未匹配到任何属性）时
     * 返回空——全 0 模式会通配全库，不是查询语义。
     */
    private Map<String, Object> querySidLookup(int[] qIdx, Set<Long> seen) {
        if (countNonZero(qIdx) == 0) return Map.of("items", List.of(), "total", 0);
        return wildcardLookup(qIdx, MAX_RESULTS, seen);
    }

    // ---- reserved-slot bypass helpers (OneRetrieval §3.4.4) ----

    /**
     * 查询编码 + reserved 覆写（论文 P3 编码端确定性）：先把 query 里已注入新词的字符
     * 剔除，用剩余部分做字典编码 —— 这样命中词的子串（如 "labubu盲盒" 里的 "lab"）不会
     * 再独立匹配到其他位置的 SID —— 再把这些词所在的槽覆写为它们的保留槽号。由于 AC
     * 自动机 build 后不可增量加词，这里用覆写表实现同等效果（query SID 层面一致）。
     */
    /** query 中命中的已注入保留词（小写比对）。 */
    private List<String> hitReservedWords(String lower) {
        List<String> hit = new ArrayList<>();
        if (reservedWords.isEmpty()) return hit;
        for (String w : reservedWords.keySet()) {
            if (lower.contains(w)) hit.add(w);
        }
        return hit;
    }

    /**
     * 查询编码 + reserved 覆写（论文 P3 编码端确定性）：先把 query 里已注入新词的字符
     * 剔除，用剩余部分做字典编码 —— 这样命中词的子串（如 "labubu盲盒" 里的 "lab"）不会
     * 再独立匹配到其他位置的 SID —— 再把这些词所在的槽覆写为它们的保留槽号。由于 AC
     * 自动机 build 后不可增量加词，这里用覆写表实现同等效果（query SID 层面一致）。
     */
    private int[] encodeWithReservedOverrides(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> hit = hitReservedWords(lower);
        if (hit.isEmpty()) return dictEncoder.encodeToIndices(query);

        // 命中的保留词字符从 query 中剔除（后续编码只作用于剩余部分），再覆写为保留槽号
        String reduced = lower;
        for (String w : hit) reduced = reduced.replace(w, "");
        int[] idx = dictEncoder.encodeToIndices(reduced);
        for (String w : hit) {
            int[] b = reservedWords.get(w);
            idx[b[0]] = b[1];
        }
        return idx;
    }

    private boolean isReserved(String pos, int idx) {
        return config != null && idx >= config.reservedMin(pos) && idx <= config.reservedMax(pos);
    }

    /**
     * 校验新词并分配槽号（不提交）。返回 {ok:true, word, pos, posIndex, slot}
     * 或 {ok:false, error}。preview（不提交）与 inject（提交）共用。
     */
    private Map<String, Object> planReservedSlot(String word, String pos, Integer requestedSlot) {
        if (word == null || word.isBlank()) return err("新词不能为空");
        word = word.toLowerCase(Locale.ROOT);
        int posIndex = POS_ORDER.indexOf(pos);
        if (posIndex < 0) return err("位置必须是 a-h");
        if (reservedWords.containsKey(word)) {
            int[] b = reservedWords.get(word);
            if (posIndex != b[0]) {
                return err("该词已注入到 " + POS_ORDER.get(b[0]) + "_" + b[1] + "，位置不符");
            }
            // 已注入词：请求了不同槽号 → 校验新槽（空闲）并计划迁移；否则沿用当前槽
            int slot = b[1];
            if (requestedSlot != null && requestedSlot != b[1]) {
                if (!isReserved(pos, requestedSlot)) {
                    return err("请求的槽号不在 " + pos + " 的 reserved 区间 [" + config.reservedMin(pos) + "," + config.reservedMax(pos) + "]");
                }
                if (usedReservedSlots.contains(pos + ":" + requestedSlot)) {
                    return err("槽 " + pos + "_" + requestedSlot + " 已被占用");
                }
                slot = requestedSlot;
            }
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("ok", true);
            plan.put("word", word);
            plan.put("pos", pos);
            plan.put("posIndex", b[0]);
            plan.put("slot", slot);
            plan.put("existing", true);  // 已注入：沿用或迁移槽位
            if (slot != b[1]) plan.put("moved_from", b[1]);
            return plan;
        }
        Integer existing = config.codebook(pos).get(word);
        if (existing != null && !isReserved(pos, existing)) {
            return err("该词已在词典 index=" + existing + "，无需 reserved 槽");
        }
        // 手动指定槽号（不做自动分配），严格校验区间与占用
        if (requestedSlot == null) {
            return err("请指定槽号 [" + config.reservedMin(pos) + "," + config.reservedMax(pos) + "]");
        }
        if (!isReserved(pos, requestedSlot)) {
            return err("请求的槽号不在 " + pos + " 的 reserved 区间 [" + config.reservedMin(pos) + "," + config.reservedMax(pos) + "]");
        }
        if (usedReservedSlots.contains(pos + ":" + requestedSlot)) {
            return err("槽 " + pos + "_" + requestedSlot + " 已被占用");
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("ok", true);
        plan.put("word", word);
        plan.put("pos", pos);
        plan.put("posIndex", posIndex);
        plan.put("slot", requestedSlot);
        return plan;
    }

    /** 预览：校验 + 分配槽号，不提交（供前端"预览槽位"）。 */
    public synchronized Map<String, Object> previewReservedWord(String word, String pos, Integer requestedSlot) {
        Map<String, Object> plan = planReservedSlot(word, pos, requestedSlot);
        if (!Boolean.TRUE.equals(plan.get("ok"))) return plan;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("word", plan.get("word"));
        result.put("pos", plan.get("pos"));
        result.put("slot", plan.get("slot"));
        result.put("reserved_sid", reservedSid((Integer) plan.get("posIndex"), (Integer) plan.get("slot")));
        if (Boolean.TRUE.equals(plan.get("existing"))) result.put("existing", true);
        if (plan.containsKey("moved_from")) result.put("moved_from", plan.get("moved_from"));
        return result;
    }

    /** query_attrs 里补上命中的 reserved 新词（AC 自动机不认识它们）。 */
    private void enrichReservedAttrs(Map<String, List<String>> attrs, String query) {
        if (reservedWords.isEmpty()) return;
        String lower = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, int[]> e : reservedWords.entrySet()) {
            if (lower.contains(e.getKey())) {
                String pos = POS_ORDER.get(e.getValue()[0]);
                List<String> words = attrs.get(pos);
                if (words != null && !words.contains(e.getKey())) words.add(e.getKey());
            }
        }
    }

    /** query SID 中被覆写的 reserved 槽（供 debug 展示）。 */
    private List<Map<String, Object>> reservedOverrideInfo(int[] qIdx) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (config == null) return list;
        for (int i = 0; i < qIdx.length; i++) {
            if (isReserved(POS_ORDER.get(i), qIdx[i])) {
                list.add(Map.of("pos", POS_ORDER.get(i), "slot", qIdx[i]));
            }
        }
        return list;
    }

    /** 只填一个保留槽的完整 6-token SID，如 "<a_0><b_2047><c_0><d_0><e_0><f_0>"。 */
    private String reservedSid(int posIndex, int slot) {
        int[] idx = new int[POS_ORDER.size()];
        idx[posIndex] = slot;
        return KaeEncoder.formatSid(idx);
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    /**
     * reserved-slot 旁路注入（论文 §3.2 / §3.4.4）：
     * 把新词绑定到某位置的一个空闲保留槽，并把该槽绑定到目标商品集合（可同时创建
     * 新商品 + 绑定已有商品）。之后任何含该词的 query 被 {@link #encodeWithReservedOverrides}
     * 编码到该槽，经 BART 路由到 item SID 后由 T 表精确命中绑定商品 —— 无需重训 BART。
     * 注意：纯 BART 模式下，保留槽号超出训练分布，BART 通常不会输出保留槽 SID，
     * 因此注入词可能检索不到；如需确定性命中需另配精确路径。仅内存，重启丢失。
     *
     * @param word            新趋势词（会转小写）
     * @param pos             槽位位置 a-f（ECOM6 组）
     * @param requestedSlot   手动指定的保留槽号（必填，区间 [1987,2046] 且未占用）
     * @param newItem         要创建的新商品字段（item_title 非空才创建）；可为 null
     * @param existingItemIds 要绑定的已有商品 id；可为 null
     */
    public synchronized Map<String, Object> injectReservedWord(
            String word, String pos, Integer requestedSlot,
            Map<String, String> newItem, List<Long> existingItemIds) {
        if (word == null || word.isBlank()) return err("新词不能为空");
        word = word.toLowerCase(Locale.ROOT);

        // 已注入过的词 → 同槽则追加绑定商品；不同槽则迁移槽位（论文：词→槽一次性，槽→商品集可增长）
        if (reservedWords.containsKey(word)) {
            int[] b = reservedWords.get(word);
            if (b[0] != POS_ORDER.indexOf(pos)) {
                return err("该词已注入到 " + POS_ORDER.get(b[0]) + "_" + b[1] + "，位置不符");
            }
            if (requestedSlot == null || requestedSlot == b[1]) {
                return appendReservedBinding(word, b, newItem, existingItemIds);
            }
            if (!isReserved(pos, requestedSlot)) {
                return err("请求的槽号不在 " + pos + " 的 reserved 区间 [" + config.reservedMin(pos) + "," + config.reservedMax(pos) + "]");
            }
            if (usedReservedSlots.contains(pos + ":" + requestedSlot)) {
                return err("槽 " + pos + "_" + requestedSlot + " 已被占用");
            }
            return moveReservedBinding(word, b, pos, requestedSlot, newItem, existingItemIds);
        }

        Map<String, Object> plan = planReservedSlot(word, pos, requestedSlot);
        if (!Boolean.TRUE.equals(plan.get("ok"))) return plan;
        word = (String) plan.get("word");
        pos = (String) plan.get("pos");
        int posIndex = (Integer) plan.get("posIndex");
        int slot = (Integer) plan.get("slot");

        // 注册词 → 槽；写进 codebook map，让 /api/lookup、/api/reverse 等 hint 端点可见
        reservedWords.put(word, new int[]{posIndex, slot});
        usedReservedSlots.add(pos + ":" + slot);
        config.codebook(pos).put(word, slot);

        // 绑定已有商品（校验存在 + 去重）
        List<Long> bound = new ArrayList<>();
        if (existingItemIds != null) {
            for (long id : existingItemIds) {
                if (itemById.containsKey(id) && !bound.contains(id)) bound.add(id);
            }
        }

        // 创建新商品：标题自动编码，但该位置强制写保留槽
        Map<String, Object> created = null;
        String title = newItem == null ? null : nz(newItem.get("item_title"));
        if (title != null && !title.isBlank()) {
            int[] idx = dictEncoder.encodeToIndices(title);
            idx[posIndex] = slot;
            created = publishItem(
                    title,
                    nz(newItem.get("brand_name")), nz(newItem.get("seller_name")),
                    nz(newItem.get("category_level1_name")), nz(newItem.get("category_level2_name")),
                    nz(newItem.get("category_level3_name")),
                    KaeEncoder.formatSid(idx));
            bound.add((Long) created.get("item_id"));
        }
        reservedSlotItems.put(pos + ":" + slot, bound);

        return reservedResult(true, word, pos, slot, posIndex, created, bound);
    }

    /**
     * 已注入词：把词→槽绑定迁移到新槽，再追加新商品。
     * 同步更新：reservedWords / usedReservedSlots / codebook 提示 / reservedSlotItems（T 绑定），
     * 以及已创建商品的 item_sid（旧槽号 → 新槽号，并修正 sidToItemIds），
     * 避免旧槽被其他词复用后前缀匹配串词。
     */
    private Map<String, Object> moveReservedBinding(
            String word, int[] b, String pos, int newSlot,
            Map<String, String> newItem, List<Long> existingItemIds) {
        int oldSlot = b[1];
        String oldKey = pos + ":" + oldSlot;
        String newKey = pos + ":" + newSlot;

        // 迁移 T 绑定：旧槽商品集合搬到新槽
        List<Long> bound = new ArrayList<>(reservedSlotItems.getOrDefault(oldKey, new ArrayList<>()));
        reservedSlotItems.remove(oldKey);
        reservedSlotItems.put(newKey, bound);

        // 更新注册表：词→新槽（b 即 reservedWords 里的数组，原地改）、占用标记、codebook 提示
        b[1] = newSlot;
        usedReservedSlots.remove(oldKey);
        usedReservedSlots.add(newKey);
        config.codebook(pos).put(word, newSlot);

        // 同步迁移已创建商品的 item_sid（其 SID 里带旧槽号）
        for (long id : bound) {
            Map<String, Object> item = itemById.get(id);
            if (item == null) continue;
            String sid = (String) item.get("item_sid");
            if (sid == null) continue;
            int[] idx = KaeEncoder.parseSid(sid);
            if (idx[b[0]] != oldSlot) continue;  // 只动带旧槽号的（= 本词创建的商品）
            idx[b[0]] = newSlot;
            String newSid = KaeEncoder.formatSid(idx);
            item.put("item_sid", newSid);
            List<Long> oldList = sidToItemIds.get(sid);
            if (oldList != null) oldList.remove(id);
            sidToItemIds.computeIfAbsent(newSid, k -> new ArrayList<>()).add(id);
        }

        // 追加新商品（复用追加逻辑）
        List<Long> added = new ArrayList<>();
        Map<String, Object> created = null;
        if (existingItemIds != null) {
            for (long id : existingItemIds) {
                if (itemById.containsKey(id) && !bound.contains(id)) { bound.add(id); added.add(id); }
            }
        }
        String title = newItem == null ? null : nz(newItem.get("item_title"));
        if (title != null && !title.isBlank()) {
            int[] idx = dictEncoder.encodeToIndices(title);
            idx[b[0]] = newSlot;
            created = publishItem(title,
                    nz(newItem.get("brand_name")), nz(newItem.get("seller_name")),
                    nz(newItem.get("category_level1_name")), nz(newItem.get("category_level2_name")),
                    nz(newItem.get("category_level3_name")), KaeEncoder.formatSid(idx));
            bound.add((Long) created.get("item_id"));
            added.add((Long) created.get("item_id"));
        }
        reservedSlotItems.put(newKey, bound);

        Map<String, Object> result = reservedResult(true, word, pos, newSlot, b[0], created, bound);
        result.put("added_ids", added);
        result.put("moved_from", oldSlot);
        return result;
    }

    /** 已注入词：只追加商品到已有槽绑定（不再重新注册词→槽）。 */
    private Map<String, Object> appendReservedBinding(
            String word, int[] b, Map<String, String> newItem, List<Long> existingItemIds) {
        String pos = POS_ORDER.get(b[0]);
        int slot = b[1];
        List<Long> bound = new ArrayList<>(reservedSlotItems.getOrDefault(pos + ":" + slot, new ArrayList<>()));
        List<Long> added = new ArrayList<>();
        Map<String, Object> created = null;
        if (existingItemIds != null) {
            for (long id : existingItemIds) {
                if (itemById.containsKey(id) && !bound.contains(id)) { bound.add(id); added.add(id); }
            }
        }
        String title = newItem == null ? null : nz(newItem.get("item_title"));
        if (title != null && !title.isBlank()) {
            int[] idx = dictEncoder.encodeToIndices(title);
            idx[b[0]] = slot;
            created = publishItem(title,
                    nz(newItem.get("brand_name")), nz(newItem.get("seller_name")),
                    nz(newItem.get("category_level1_name")), nz(newItem.get("category_level2_name")),
                    nz(newItem.get("category_level3_name")), KaeEncoder.formatSid(idx));
            bound.add((Long) created.get("item_id"));
            added.add((Long) created.get("item_id"));
        }
        reservedSlotItems.put(pos + ":" + slot, bound);
        Map<String, Object> result = reservedResult(true, word, pos, slot, b[0], created, bound);
        result.put("added_ids", added);
        return result;
    }

    private Map<String, Object> reservedResult(boolean ok, String word, String pos, int slot,
                                                int posIndex, Map<String, Object> created, List<Long> bound) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok);
        result.put("word", word);
        result.put("pos", pos);
        result.put("slot", slot);
        result.put("reserved_sid", reservedSid(posIndex, slot));
        if (created != null) result.put("created_item", created);
        result.put("bound_ids", bound);
        result.put("bound_count", bound.size());
        return result;
    }

    /** 当前所有 reserved 注入（供运营查看）。 */
    public List<Map<String, Object>> reservedBindings() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, int[]> e : reservedWords.entrySet()) {
            int[] b = e.getValue();
            int count = reservedSlotItems.getOrDefault(POS_ORDER.get(b[0]) + ":" + b[1],
                    Collections.emptyList()).size();
            list.add(Map.of("word", e.getKey(), "pos", POS_ORDER.get(b[0]),
                    "slot", b[1], "bound_count", count));
        }
        return list;
    }

    /**
     * 删除已注入词：解除词→槽绑定、释放槽位、移除 codebook 提示与 T 绑定。
     * 该词创建的商品（SID 带保留槽）一并删除；绑定的已有商品仅解绑、保留。
     */
    public synchronized Map<String, Object> removeReservedWord(String word) {
        if (word == null || word.isBlank()) return err("新词不能为空");
        word = word.toLowerCase(Locale.ROOT);
        int[] b = reservedWords.get(word);
        if (b == null) return err("该词未注入");
        String pos = POS_ORDER.get(b[0]);
        int slot = b[1];
        String key = pos + ":" + slot;

        // 区分槽上绑定的商品：SID 带保留槽的 = 本词创建的商品（删除）；其余 = 已有商品（解绑）
        List<Long> created = new ArrayList<>();
        List<Long> existing = new ArrayList<>();
        for (long id : reservedSlotItems.getOrDefault(key, new ArrayList<>())) {
            Map<String, Object> item = itemById.get(id);
            if (item == null) continue;
            String sid = (String) item.get("item_sid");
            if (sid != null) {
                int[] idx = KaeEncoder.parseSid(sid);
                if (idx[b[0]] == slot) { created.add(id); continue; }
            }
            existing.add(id);
        }

        // 删除创建的商品：从 itemById 与 sidToItemIds 移除
        for (long id : created) {
            Map<String, Object> item = itemById.remove(id);
            if (item != null) {
                String sid = (String) item.get("item_sid");
                if (sid != null) {
                    List<Long> list = sidToItemIds.get(sid);
                    if (list != null) list.remove(id);
                }
            }
        }
        // 从其他词/坑位的绑定中清理已删除的商品 id
        for (List<Long> list : reservedSlotItems.values()) list.removeAll(created);

        // 解除词→槽、释放槽位、移除 codebook 提示与 T 绑定
        reservedWords.remove(word);
        usedReservedSlots.remove(key);
        config.codebook(pos).remove(word);
        reservedSlotItems.remove(key);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("word", word);
        r.put("pos", pos);
        r.put("slot", slot);
        r.put("removed_items", created);
        r.put("unbound_items", existing);
        r.put("bound_count", created.size() + existing.size());
        return r;
    }

    // ---- 坑位系统（运营在关键词下固定商品排序位）----

    /** query 命中的坑位关键词：包含匹配，多个命中取最长（最具体）的。 */
    private String matchSlotKeyword(String query) {
        if (keywordSlots.isEmpty() || query == null) return null;
        String lower = query.toLowerCase(Locale.ROOT);
        String keyword = null;
        for (String k : keywordSlots.keySet()) {
            if (lower.contains(k) && (keyword == null || k.length() > keyword.length())) keyword = k;
        }
        return keyword;
    }

    /**
     * 按坑位重排搜索结果：query 命中坑位关键词时，把指定商品放到指定排序位（1 起）。
     * 商品已在结果中 → 移动到该位、其余顺延；不在结果中 → 强制插入到该位（广告坑位语义）。
     */
    private List<Map<String, Object>> applySlotRanking(String query, List<Map<String, Object>> items) {
        String keyword = matchSlotKeyword(query);
        if (keyword == null) return items;
        Map<Integer, Long> slots = keywordSlots.get(keyword);
        if (slots == null || slots.isEmpty()) return items;

        List<Integer> ranks = new ArrayList<>(slots.keySet());
        Collections.sort(ranks);
        List<Map<String, Object>> result = new ArrayList<>(items);
        for (int rank : ranks) {
            Map<String, Object> item = itemById.get(slots.get(rank));
            if (item == null) continue;
            long id = ((Number) item.get("item_id")).longValue();
            result.removeIf(m -> ((Number) m.get("item_id")).longValue() == id);
            result.add(Math.min(rank - 1, result.size()), item);
        }
        return result;
    }

    /** 设置坑位：keyword 下的 rank 位绑定 itemId（同 rank 覆盖）。 */
    public synchronized Map<String, Object> addKeywordSlot(String keyword, Long itemId, Integer rank) {
        if (keyword == null || keyword.isBlank()) return err("关键词不能为空");
        keyword = keyword.toLowerCase(Locale.ROOT);
        if (rank == null || rank < 1) return err("排序号必须 ≥ 1");
        if (itemId == null || !itemById.containsKey(itemId)) return err("商品 " + itemId + " 不存在");
        keywordSlots.computeIfAbsent(keyword, k -> new ConcurrentHashMap<>()).put(rank, itemId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("keyword", keyword);
        r.put("rank", rank);
        r.put("item_id", itemId);
        return r;
    }

    /** 移除 keyword 下某个坑位；该关键词没有坑位后整体删除。 */
    public synchronized Map<String, Object> removeKeywordSlot(String keyword, Integer rank) {
        if (keyword == null || rank == null) return err("参数缺失");
        keyword = keyword.toLowerCase(Locale.ROOT);
        Map<Integer, Long> slots = keywordSlots.get(keyword);
        if (slots == null || slots.remove(rank) == null) {
            return err("坑位 " + keyword + " 第 " + rank + " 位不存在");
        }
        if (slots.isEmpty()) keywordSlots.remove(keyword);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("keyword", keyword);
        r.put("rank", rank);
        return r;
    }

    /** 当前所有坑位：keyword → 按 rank 排序的 [{rank, item_id}]。 */
    public List<Map<String, Object>> keywordSlotsList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, Long>> e : keywordSlots.entrySet()) {
            List<Map<String, Object>> slots = new ArrayList<>();
            for (Map.Entry<Integer, Long> s : e.getValue().entrySet()) {
                slots.add(Map.of("rank", s.getKey(), "item_id", s.getValue()));
            }
            slots.sort((a, b) -> Integer.compare((Integer) a.get("rank"), (Integer) b.get("rank")));
            out.add(Map.of("keyword", e.getKey(), "slots", slots));
        }
        out.sort((a, b) -> ((String) a.get("keyword")).compareTo((String) b.get("keyword")));
        return out;
    }

    private int countNonZero(String sid) {
        if (sid == null) return 0;
        int[] idx = KaeEncoder.parseSid(sid);
        int n = 0;
        for (int v : idx) if (v != 0) n++;
        return n;
    }

    // ---- Load index files ----

    @SuppressWarnings("unchecked")
    private Map<String, List<Long>> loadSidToItemIds() throws IOException {
        Path path = Paths.get("src/main/resources/sid_to_items.json");
        Map<String, Object> wrapper = GSON.fromJson(
                Files.newBufferedReader(path),
                new TypeToken<Map<String, Object>>() {}.getType());
        Map<String, Object> index = (Map<String, Object>) wrapper.get("index");

        Map<String, List<Long>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : index.entrySet()) {
            List<Double> raw = (List<Double>) e.getValue();
            List<Long> ids = new ArrayList<>();
            for (Double d : raw) ids.add(d.longValue());
            result.put(e.getKey(), ids);
        }
        return result;
    }

    private Map<Long, Map<String, Object>> loadItemDetails() throws IOException {
        // 全量索引是 JSONL（每行一条 {item_id, item_title, item_sid}），流式解析，
        // 避免 Gson 一次性物化 6.6M 条的 List 中间态（省约 2GB 峰值堆）。
        Path path = Paths.get("src/main/resources/items_with_sid.json");
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>(1 << 22);
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> item = GSON.fromJson(line,
                        new TypeToken<Map<String, Object>>() {}.getType());
                long id = ((Number) item.get("item_id")).longValue();
                result.put(id, item);
                count++;
                if (count % 1_000_000 == 0) {
                    System.out.printf("    loading %,d items...%n", count);
                }
            }
        }
        return result;
    }

    @Override
    public void close() throws Exception {
        if (bart != null) bart.close();
    }

    /** Access the SID→itemIds map for external use. */
    public Map<String, List<Long>> getSidToItemIds() { return sidToItemIds; }
    /** Access the itemId→item map for external use. */
    public Map<Long, Map<String, Object>> getItemById() { return itemById; }
    /** Access the codebook (word→index) for one position, for front-end hints. */
    public Map<String, Integer> codebook(String pos) {
        return dictEncoder.getConfig().codebook(pos);
    }

    // ---- 按 item_sid 查询商品（检索页 "按SID查询" tab）----

    /**
     * 按 item SID 模式直接通配查找商品：0 = 通配（该位置任意值），非 0 = 精确匹配。
     * 不做 BART 路由——输入本身就是要匹配的 item SID 模式，0 位不约束。
     */
    public Map<String, Object> searchByItemSid(String sid) {
        int[] pattern = KaeEncoder.parseSid(sid);
        Map<String, Object> r = wildcardLookup(pattern, MAX_RESULTS, new HashSet<>());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("item_sid", KaeEncoder.formatSid(pattern));
        debug.put("item_sid_fill", countNonZero(pattern));
        debug.put("match_method", items.isEmpty() ? "none" : "wildcard");
        debug.put("wildcard_hits", r.get("total"));
        debug.put("items_found", items.size());
        debug.put("items", items);
        return debug;
    }

    /**
     * SID 反查词表：把每个非 0 位置的索引对应到该词表槽的词（簇槽只显示前 6 个，
     * word_count 给出总数）。保留槽标注 reserved；空闲保留槽无词。供前端"词表对应词"展示。
     */
    public Map<String, Object> decodeSid(String sid) {
        int[] idx = KaeEncoder.parseSid(sid);
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (int p = 0; p < POS_ORDER.size(); p++) {
            String pos = POS_ORDER.get(p);
            int v = idx[p];
            List<String> words = new ArrayList<>();
            int count = 0;
            if (v != 0) {
                Map<String, Integer> cb = codebook(pos);
                if (cb != null) {
                    for (Map.Entry<String, Integer> e : cb.entrySet()) {
                        if (e.getValue() == v) {
                            count++;
                            if (words.size() < 6) words.add(e.getKey());
                        }
                    }
                }
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("pos", pos);
            t.put("idx", v);
            t.put("nonempty", v != 0);
            t.put("reserved", v != 0 && isReserved(pos, v));
            t.put("words", words);
            t.put("word_count", count);
            tokens.add(t);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sid", sid);
        r.put("fill", countNonZero(idx));
        r.put("tokens", tokens);
        return r;
    }

    /**
     * 单个词表槽位反查：输入一段 SID，如 "a_100" 或 "<a_100>" → 返回该槽绑定的全部关键词
     * （簇槽会很多，如 b_95 → 羽毛球拍/网球/乒乓球拍…）。返回 {ok, pos, idx, reserved,
     * empty_slot, word_count, words[全部]}；格式非法返回 {ok:false, error}。
     */
    public Map<String, Object> slotWords(String slot) {
        if (slot == null) return err("参数缺失");
        String s = slot.trim();
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1);
        int u = s.indexOf('_');
        if (u <= 0 || u == s.length() - 1) return err("格式应为 pos_idx，如 a_100");
        String pos = s.substring(0, u);
        if (POS_ORDER.indexOf(pos) < 0) return err("位置必须是 a-h");
        int idx;
        try {
            idx = Integer.parseInt(s.substring(u + 1));
        } catch (NumberFormatException e) {
            return err("索引必须是数字");
        }
        if (idx < 0) return err("索引不能为负");

        List<String> words = new ArrayList<>();
        Map<String, Integer> cb = codebook(pos);
        if (cb != null) {
            for (Map.Entry<String, Integer> e : cb.entrySet()) {
                if (e.getValue() == idx) words.add(e.getKey());
            }
        }
        words.sort(String::compareTo);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("pos", pos);
        r.put("idx", idx);
        r.put("reserved", isReserved(pos, idx));
        r.put("empty_slot", idx == 0);
        r.put("word_count", words.size());
        r.put("words", words);
        return r;
    }

    private static int countNonZero(int[] idx) {
        int n = 0;
        for (int v : idx) if (v != 0) n++;
        return n;
    }

    // Sentinel id base for in-memory published products — far above any real
    // catalog id (~4M range), so it can never collide with the loaded data.
    private long nextNewId = 9_000_000_000_000L;

    /** Auto-encode a product title into a 6-token item SID (publish preview). */
    public String encodeTitle(String title) {
        return dictEncoder.encode(title);
    }

    /**
     * Publish a new product into the in-memory index (itemById + sidToItemIds).
     * Immediately searchable via {@link #search} — no restart needed.
     * In-memory only; lost on restart. `itemSid` is used verbatim, so the
     * caller controls which 6 slots the product occupies.
     */
    public synchronized Map<String, Object> publishItem(
            String itemTitle, String brandName, String sellerName,
            String cat1, String cat2, String cat3, String itemSid) {
        long id = nextNewId++;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("item_id", id);
        item.put("item_title", itemTitle);
        item.put("brand_name", nz(brandName));
        item.put("seller_name", nz(sellerName));
        item.put("category_level1_name", nz(cat1));
        item.put("category_level2_name", nz(cat2));
        item.put("category_level3_name", nz(cat3));
        item.put("item_sid", itemSid);
        itemById.put(id, item);
        sidToItemIds.computeIfAbsent(itemSid, k -> new ArrayList<>()).add(id);
        return item;
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "" : s; }

    // ---- Demo ----
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(65));
        System.out.println("  RetrievalService Demo");
        System.out.println("=".repeat(65));

        RetrievalService service = new RetrievalService();

        String[] queries = {
            "小米黑色手机壳",
            "冬季加厚羽绒服男款",
            "女士显瘦牛仔裤",
            "白色跑步鞋",
            "不锈钢保温杯",
        };

        int k = 1;  // BART beam size

        for (String query : queries) {
            System.out.printf("%n%s%n", "-".repeat(65));
            Map<String, Object> debug = service.searchDebug(query, k);

            System.out.printf("Query:        %s%n", debug.get("query"));
            System.out.printf("Query SID:    %s  (fill=%d)%n",
                    debug.get("query_sid"), debug.get("query_fill"));
            System.out.printf("Method:       %s%n", debug.get("match_method"));
            System.out.printf("BART candidates: %s%n", debug.get("bart_candidates"));
            System.out.printf("Items found:  %d%n", debug.get("items_found"));
            System.out.printf("Time: encode=%.0fµs  route=%.0fµs  lookup=%.0fµs%n",
                    (double) debug.get("time_encode_us"),
                    (double) debug.get("time_route_us"),
                    (double) debug.get("time_lookup_us"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) debug.get("items");
            System.out.println("Top results:");
            for (int i = 0; i < Math.min(5, items.size()); i++) {
                Map<String, Object> item = items.get(i);
                System.out.printf("  %d. [%s] %s | %s | %s%n",
                        i + 1,
                        item.get("brand_name"),
                        item.get("item_title"),
                        item.get("category_level1_name"),
                        item.get("item_sid"));
            }
        }

        service.close();
        System.out.printf("%n%s%nDone!%n", "=".repeat(65));
    }
}
