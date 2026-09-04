// Thin wrapper around the Spring Boot search API.
// Dev: Vite proxies /api → localhost:8080. Prod: same origin.

/**
 * @param {string} query
 * @param {number} k BART beam size
 * @returns {Promise<Object>} the searchDebug payload (query_sid, query_attrs,
 *   match_method, items, timing fields, ...)
 */
export async function fetchSearch(query, k = 3) {
  const url = `/api/search?q=${encodeURIComponent(query)}&k=${k}`
  const res = await fetch(url)
  if (!res.ok) {
    throw new Error(`搜索失败 (HTTP ${res.status})`)
  }
  return res.json()
}

/** Manually route an edited querySID through BART + T lookup. */
export async function searchBySid(sid, k = 3) {
  const url = `/api/search-by-sid?sid=${encodeURIComponent(sid)}&k=${k}`
  const res = await fetch(url)
  if (!res.ok) {
    throw new Error(`路由失败 (HTTP ${res.status})`)
  }
  return res.json()
}

/** Word → index across all 6 positions: { word, matches: [{pos, index}] }. */
export async function lookupWord(word) {
  const url = `/api/lookup?word=${encodeURIComponent(word)}`
  const res = await fetch(url)
  return res.ok ? res.json() : { word, matches: [] }
}

/** Index → words for one position: what does <a_1609> mean? */
export async function reverseLookup(pos, idx) {
  const url = `/api/reverse?pos=${pos}&idx=${encodeURIComponent(idx)}`
  const res = await fetch(url)
  return res.ok ? res.json() : { pos, idx, words: [] }
}

/** Auto-encode a product title into a 6-token item SID (informational). */
export async function itemPreview(title) {
  const url = `/api/item-preview?title=${encodeURIComponent(title)}`
  const res = await fetch(url)
  if (!res.ok) throw new Error(`预览失败 (HTTP ${res.status})`)
  return res.json()
}

/** 按 item SID 模式直接通配查找商品：0=通配，非0=精确（不做 BART 路由）。 */
export async function searchByItemSid(sid) {
  const url = `/api/search-by-itemsid?sid=${encodeURIComponent(sid)}`
  const res = await fetch(url)
  if (!res.ok) throw new Error(`查询失败 (HTTP ${res.status})`)
  return res.json()
}

/** SID 反查词表：{sid, fill, tokens:[{pos, idx, nonempty, reserved, words, word_count}]} */
export async function decodeSid(sid) {
  const url = `/api/sid-words?sid=${encodeURIComponent(sid)}`
  const res = await fetch(url)
  if (!res.ok) throw new Error(`词表反查失败 (HTTP ${res.status})`)
  return res.json()
}

/** 单个词表槽位反查：a_100 → {ok, pos, idx, reserved, empty_slot, word_count, words[全部]} */
export async function slotWords(slot) {
  const url = `/api/slot-words?slot=${encodeURIComponent(slot)}`
  const res = await fetch(url)
  if (!res.ok) throw new Error(`词表反查失败 (HTTP ${res.status})`)
  return res.json()
}

/** reserved-slot 预览：word+pos+slot → 校验手动指定槽号（不提交）。 */
export async function reservedPreview(word, pos, slot) {
  const res = await fetch('/api/reserved-preview', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ word, pos, slot }),
  })
  if (!res.ok) throw new Error(`预览失败 (HTTP ${res.status})`)
  return res.json()
}

/** reserved-slot 注入：{word, pos, slot?, new_item?, item_ids?} → 提交绑定/创建。 */
export async function reservedBind(payload) {
  const res = await fetch('/api/reserved-bind', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error(`注入失败 (HTTP ${res.status})`)
  return res.json()
}

/** 当前 reserved 注入列表（已占用槽）。 */
export async function reservedList() {
  const res = await fetch('/api/reserved-list')
  if (!res.ok) throw new Error(`列表获取失败 (HTTP ${res.status})`)
  return res.json()
}

/** 删除已注入词：释放槽位，删除该词创建的商品，解绑已有商品。 */
export async function reservedRemove(word) {
  const res = await fetch('/api/reserved-remove', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ word }),
  })
  if (!res.ok) throw new Error(`删除失败 (HTTP ${res.status})`)
  return res.json()
}

/** 坑位：设置 keyword 下 rank 位 = item_id。 */
export async function slotAdd(keyword, itemId, rank) {
  const res = await fetch('/api/slot-add', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword, item_id: itemId, rank }),
  })
  if (!res.ok) throw new Error(`坑位设置失败 (HTTP ${res.status})`)
  return res.json()
}

/** 坑位：移除 keyword 下 rank 位。 */
export async function slotRemove(keyword, rank) {
  const res = await fetch('/api/slot-remove', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword, rank }),
  })
  if (!res.ok) throw new Error(`坑位移除失败 (HTTP ${res.status})`)
  return res.json()
}

/** 当前所有坑位（keyword → [{rank, item_id}]）。 */
export async function slotList() {
  const res = await fetch('/api/slot-list')
  if (!res.ok) throw new Error(`坑位列表获取失败 (HTTP ${res.status})`)
  return res.json()
}
