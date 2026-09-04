// Shared constants for SID positions (a–h map to the 8 KAE groups).
export const POS_NAMES = {
  a: '核心品类',
  b: '适用人群与场景',
  c: '外观风格',
  d: '功能与规格',
  e: '材质与修饰',
  f: '品牌与工艺',
  g: '型号与参数',
  h: '实体与杂项',
}

export const POS_ORDER = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']

// 每位置核心码本 SID 上限（sid_max，与 kae_config.json / codebook_config.json 一致）；
// 保留槽区间 = (sid_max, sid_max + RESERVED_PER_POS]。
export const POS_SID_MAX = { a: 2189, b: 1330, c: 2099, d: 1582, e: 1030, f: 563, g: 264, h: 151 }
export const RESERVED_PER_POS = 30

/** 某位置的保留槽区间 [min, max]。 */
export function reservedRange(pos) {
  const mx = POS_SID_MAX[pos] ?? 0
  return { min: mx + 1, max: mx + RESERVED_PER_POS }
}

export const METHOD_LABELS = {
  bart_exact: 'BART 精确匹配',
  prefix_match: '前缀匹配',
  query_sid_wildcard: 'querySID 通配',
  'query_sid+bart': 'querySID 通配 + BART',
  none: '无匹配',
}

/** Parse "<a_1609><b_0>..." into [{pos, idx, nonzero}, ...]. */
export function parseSid(sid) {
  if (!sid) return []
  return POS_ORDER.map((pos) => {
    const m = sid.match(new RegExp(`<${pos}_(\\d+)>`))
    const idx = m ? Number(m[1]) : 0
    return { pos, idx, nonzero: idx !== 0 }
  })
}

/** Build "<a_1609><b_0>..." from 6 numeric indices. */
export function formatSid(indices) {
  return POS_ORDER.map((p, i) => `<${p}_${indices[i] ?? 0}>`).join('')
}
