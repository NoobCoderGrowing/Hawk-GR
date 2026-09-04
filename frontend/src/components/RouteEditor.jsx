import { useEffect, useRef, useState } from 'react'
import { POS_NAMES, POS_ORDER, parseSid, formatSid } from '../constants.js'
import { lookupWord, reverseLookup } from '../api.js'

/**
 * Editable-routing panel: 6 slots (a–f) each with a numeric index input,
 * a word→index hint box, and a reverse hint showing what the current index means.
 * "重新路由" re-runs BART routing with the edited querySID.
 */
export default function RouteEditor({ sid, onRoute, actionLabel = '重新路由' }) {
  const [indices, setIndices] = useState(() => parseSid(sid).map((t) => t.idx))
  const [hints, setHints] = useState({}) // pos -> { query, results: [{pos,index}] }
  const [reverse, setReverse] = useState({}) // pos -> [words, ...]
  const timer = useRef(null)

  // Refresh "what does this index mean" whenever any slot changes.
  useEffect(() => {
    const t = setTimeout(() => {
      indices.forEach((idx, i) => {
        const pos = POS_ORDER[i]
        if (idx !== 0) {
          reverseLookup(pos, idx).then((r) =>
            setReverse((prev) => ({ ...prev, [pos]: r.words }))
          )
        }
      })
    }, 80)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [indices.join(',')])

  useEffect(() => () => clearTimeout(timer.current), [])

  function setIndex(i, raw) {
    const v = raw === '' ? 0 : Number(raw)
    if (Number.isNaN(v)) return
    setIndices((prev) => prev.map((x, j) => (j === i ? v : x)))
  }

  function onHintChange(pos, text) {
    setHints((h) => ({
      ...h,
      [pos]: { query: text, results: text.trim() ? h[pos]?.results : [] },
    }))
    clearTimeout(timer.current)
    timer.current = setTimeout(async () => {
      const res = await lookupWord(text)
      setHints((h) => ({ ...h, [pos]: { query: text, results: res.matches } }))
    }, 200)
  }

  function fillFromMatch(match) {
    const i = POS_ORDER.indexOf(match.pos)
    if (i >= 0) setIndex(i, match.index)
  }

  return (
    <section className="route-editor">
      <div className="route-editor-header">
        <code className="route-sid">{formatSid(indices)}</code>
        <button className="route-btn" onClick={() => onRoute(formatSid(indices))}>
          {actionLabel}
        </button>
      </div>
      <div className="slots">
        {POS_ORDER.map((pos, i) => {
          const hint = hints[pos]
          const rev = reverse[pos]
          return (
            <div className="slot" key={pos}>
              <span className={`slot-pos ${pos}`}>
                {pos}<em>{POS_NAMES[pos]}</em>
              </span>
              <input
                className="slot-idx"
                type="number"
                min="0"
                value={indices[i]}
                title={`${pos}_index`}
                onChange={(e) => setIndex(i, e.target.value)}
              />
              <div className="slot-hint">
                <input
                  className="slot-hint-input"
                  placeholder="输入词条查 index"
                  value={hint?.query ?? ''}
                  onChange={(e) => onHintChange(pos, e.target.value)}
                />
                {hint?.results && hint.results.length > 0 && (
                  <ul className="slot-hint-list">
                    {hint.results.map((m) => (
                      <li key={m.pos} onClick={() => fillFromMatch(m)}>
                        <b>{m.index}</b> {m.pos}({POS_NAMES[m.pos]}) — 点击填入
                      </li>
                    ))}
                  </ul>
                )}
                {hint?.query && hint.results && hint.results.length === 0 && (
                  <div className="slot-hint-empty">未收录该词</div>
                )}
              </div>
              {indices[i] !== 0 && (
                <div className="reverse-hint" title="该 index 代表的词">
                  {rev ? rev.slice(0, 12).join('、') : '…'}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </section>
  )
}
