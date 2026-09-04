package hawk.gr.web;

import hawk.gr.RetrievalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search REST API for the Hawk-GR web page.
 * <p>
 * Passes through the full debug payload from
 * {@link RetrievalService#searchDebug(String, int)}: query SID, per-position
 * matched attributes, match method, BART candidate count, per-stage timings,
 * and the resolved item records.
 *
 * <p>Note: the underlying BART path keeps all beam-search state method-local,
 * so a request is safe to run on any thread; the DJL tokenizer is the only
 * shared object and is effectively read-only per call. For this demo the
 * per-request instance field read is fine.
 */
@RestController
public class SearchController {

    private final RetrievalService service;

    public SearchController(RetrievalService service) {
        this.service = service;
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(
            @RequestParam("q") String query,
            @RequestParam(value = "k", defaultValue = "3") int k) throws Exception {
        return service.searchDebug(query, k);
    }

    /** Cheap health probe — returns true once the service bean is up. */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    private static final List<String> POSITIONS = List.of("a", "b", "c", "d", "e", "f", "g", "h");

    /**
     * Codebook lookup tolerating case: try the word as-is, then lowercased.
     * (DictEncoder lowercases only user input, not the codebook itself.)
     */
    private static Integer lookupIndex(Map<String, Integer> cb, String word) {
        Integer idx = cb.get(word);
        if (idx != null) return idx;
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.equals(word) ? null : cb.get(lower);
    }

    /** Manual SID routing: re-run BART routing + T lookup for an edited querySID. */
    @GetMapping("/api/search-by-sid")
    public Map<String, Object> searchBySid(
            @RequestParam("sid") String sid,
            @RequestParam(value = "k", defaultValue = "3") int k) throws Exception {
        return service.searchWithQuerySid(sid, k);
    }

    /** Word → index across all 6 positions: {"word":"网球","matches":[{"pos":"a","index":1609},...]}. */
    @GetMapping("/api/lookup")
    public Map<String, Object> lookup(@RequestParam("word") String word) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (String pos : POSITIONS) {
            Integer idx = lookupIndex(service.codebook(pos), word);
            if (idx != null) matches.add(Map.of("pos", pos, "index", idx));
        }
        return Map.of("word", word, "matches", matches);
    }

    /** Prefix autocomplete within one position: ?word=网&pos=a → top-20 words by frequency rank. */
    @GetMapping("/api/suggest")
    public Map<String, Object> suggest(
            @RequestParam("word") String word,
            @RequestParam(value = "pos", defaultValue = "a") String pos) {
        List<Map.Entry<String, Integer>> hits = new ArrayList<>();
        for (Map.Entry<String, Integer> e : service.codebook(pos).entrySet()) {
            if (e.getKey().startsWith(word)) hits.add(e);
        }
        hits.sort((x, y) -> Integer.compare(x.getValue(), y.getValue()));  // smaller index = more frequent
        List<Map<String, Object>> words = new ArrayList<>();
        for (int i = 0; i < Math.min(20, hits.size()); i++) {
            Map.Entry<String, Integer> e = hits.get(i);
            words.add(Map.of("word", e.getKey(), "index", e.getValue()));
        }
        return Map.of("pos", pos, "word", word, "words", words);
    }

    /** Index → words (reverse lookup): what does <a_1609> mean? */
    @GetMapping("/api/reverse")
    public Map<String, Object> reverse(
            @RequestParam("pos") String pos,
            @RequestParam("idx") int idx) {
        List<String> words = new ArrayList<>();
        for (Map.Entry<String, Integer> e : service.codebook(pos).entrySet()) {
            if (e.getValue() == idx) words.add(e.getKey());
        }
        words.sort(String::compareTo);
        return Map.of("pos", pos, "idx", idx, "words", words);
    }

    /** Auto-encode a product title into a 6-token item SID (informational). */
    @GetMapping("/api/item-preview")
    public Map<String, Object> itemPreview(@RequestParam("title") String title) {
        return Map.of("title", title, "item_sid", service.encodeTitle(title));
    }

    /** 按 item SID 模式直接通配查找商品：0=通配，非0=精确（不做 BART 路由）。 */
    @GetMapping("/api/search-by-itemsid")
    public Map<String, Object> searchByItemSid(@RequestParam("sid") String sid) {
        return service.searchByItemSid(sid);
    }

    /** SID 反查词表：<a_1432><c_1067>… → 每个非 0 位置对应的词表词。 */
    @GetMapping("/api/sid-words")
    public Map<String, Object> sidWords(@RequestParam("sid") String sid) {
        return service.decodeSid(sid);
    }

    /** 单个词表槽位反查：?slot=a_100 → 该槽绑定的全部关键词。 */
    @GetMapping("/api/slot-words")
    public Map<String, Object> slotWords(@RequestParam("slot") String slot) {
        return service.slotWords(slot);
    }

    /** reserved-slot 预览：{word, pos, slot?} → 分配槽号（不提交）。 */
    @PostMapping("/api/reserved-preview")
    public Map<String, Object> reservedPreview(@RequestBody Map<String, Object> body) {
        return service.previewReservedWord(
                str(body.get("word")), str(body.get("pos")), intOrNull(body.get("slot")));
    }

    /** reserved-slot 注入：{word, pos, slot?, new_item?, item_ids?} → 提交绑定/创建。 */
    @PostMapping("/api/reserved-bind")
    public Map<String, Object> reservedBind(@RequestBody Map<String, Object> body) {
        return service.injectReservedWord(
                str(body.get("word")), str(body.get("pos")), intOrNull(body.get("slot")),
                stringMap(body.get("new_item")), longList(body.get("item_ids")));
    }

    /** 当前 reserved 注入列表（供运营查看已占用槽）。 */
    @GetMapping("/api/reserved-list")
    public Map<String, Object> reservedList() {
        return Map.of("bindings", service.reservedBindings());
    }

    /** 删除已注入词：{word} → 释放槽位、移除该词创建的商品、解绑已有商品。 */
    @PostMapping("/api/reserved-remove")
    public Map<String, Object> reservedRemove(@RequestBody Map<String, Object> body) {
        return service.removeReservedWord(str(body.get("word")));
    }

    /** 坑位：设置 keyword 下 rank 位 = item_id。 */
    @PostMapping("/api/slot-add")
    public Map<String, Object> slotAdd(@RequestBody Map<String, Object> body) {
        return service.addKeywordSlot(
                str(body.get("keyword")),
                body.get("item_id") instanceof Number n ? n.longValue() : null,
                intOrNull(body.get("rank")));
    }

    /** 坑位：移除 keyword 下 rank 位。 */
    @PostMapping("/api/slot-remove")
    public Map<String, Object> slotRemove(@RequestBody Map<String, Object> body) {
        return service.removeKeywordSlot(str(body.get("keyword")), intOrNull(body.get("rank")));
    }

    /** 当前所有坑位。 */
    @GetMapping("/api/slot-list")
    public Map<String, Object> slotList() {
        return Map.of("slots", service.keywordSlotsList());
    }

    /** 从 JSON 对象提取 String→String 字段（new_item）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) raw;
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : List.of("item_title", "brand_name", "seller_name",
                "category_level1_name", "category_level2_name", "category_level3_name")) {
            out.put(key, str(m.get(key)));
        }
        return out;
    }

    /** 从 JSON 数组提取 Long 列表（item_ids）。 */
    private static List<Long> longList(Object raw) {
        if (!(raw instanceof List)) return null;
        List<Long> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o instanceof Number n) out.add(n.longValue());
        }
        return out.isEmpty() ? null : out;
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
