import { POS_NAMES, POS_ORDER, METHOD_LABELS, parseSid } from '../constants.js'

/** Format a µs timing value, tolerating absence (manual-SID route has none). */
function fmtUs(us) {
  if (us == null) return '—'
  return `${(us / 1000).toFixed(2)}ms`
}

export default function DebugPanel({ debug }) {
  const tokens = parseSid(debug.query_sid)
  const attrs = debug.query_attrs || {}
  const fill = debug.query_fill ?? tokens.filter((t) => t.nonzero).length
  const methodLabel = METHOD_LABELS[debug.match_method] ?? debug.match_method ?? '—'

  return (
    <section className="debug-panel">
      <div className="sid-line">
        <span className="label">querySID</span>
        <code className="sid">{debug.query_sid}</code>
        <span className="fill-badge">fill={fill}</span>
        {debug.query_sid_hits != null && (
          <span className="fill-badge">querySID 通配 {debug.query_sid_hits} 件·排前</span>
        )}
      </div>

      <div className="tokens">
        {tokens.map((t) => (
          <span key={t.pos} className={`token ${t.nonzero ? 'on' : 'off'}`}>
            <span className="token-pos">{t.pos}</span>
            <span className="token-idx">{t.nonzero ? t.idx : '∅'}</span>
            <span className="token-name">{POS_NAMES[t.pos]}</span>
          </span>
        ))}
      </div>

      <div className="attrs">
        {POS_ORDER.filter((pos) => (attrs[pos] || []).length > 0).map((pos) => (
          <div key={pos} className="attr-row">
            <span className={`attr-pos ${pos}`}>{pos}({POS_NAMES[pos]})</span>
            <span className="attr-words">{attrs[pos].join('、')}</span>
          </div>
        ))}
        {POS_ORDER.every((pos) => (attrs[pos] || []).length === 0) && (
          <div className="attr-row muted">未匹配到任何属性</div>
        )}
      </div>

      {debug.bart_sids?.length > 0 && (
        <div className="bart-sids-block">
          <div className="bart-sids-label">
            BART 生成的商品 SID → 归 0 通配查找
            <span className="bart-hits-tip">×N = 通配命中的商品数（0 位不约束）</span>
          </div>
          <div className="bart-sids">
            {(debug.bart_masked_sids?.length ? debug.bart_masked_sids : debug.bart_sids).map((sid, i) => {
              const n = debug.bart_masked_hits?.[sid] ?? debug.bart_candidate_hits?.[sid] ?? 0
              return (
                <code key={i} className={`bart-sid ${n > 0 ? 'hit' : ''}`}>
                  <span className="bart-sid-idx">{i + 1}.</span> {sid}
                  {n > 0 ? ` ×${n}` : '（通配无匹配）'}
                </code>
              )
            })}
          </div>
          {debug.bart_masked_sids?.length > 0 && (
            <div className="bart-raw-sids">
              BART 原始生成：{debug.bart_sids.join('  ')}
            </div>
          )}
        </div>
      )}

      <div className="meta">
        <span>方法: <b>{methodLabel}</b></span>
        {debug.query_all_zero && <span>query_sid 全 0（未匹配到属性），不返回结果</span>}
        <span>BART 候选: {debug.bart_candidates}</span>
        <span>商品数: {debug.items_found}</span>
        <span>编码: {fmtUs(debug.time_encode_us)}</span>
        <span>路由: {fmtUs(debug.time_route_us)}</span>
        <span>查找: {fmtUs(debug.time_lookup_us)}</span>
      </div>
    </section>
  )
}
