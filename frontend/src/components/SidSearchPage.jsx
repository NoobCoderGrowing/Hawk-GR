import { useState } from 'react'
import { slotWords } from '../api.js'
import { POS_NAMES } from '../constants.js'

/**
 * 词表槽位关键词 tab：输入一段 SID 槽位（如 a_100 / <b_95>），
 * 反查词表并显示该槽绑定的全部关键词。不做商品检索。
 */
export default function SidSearchPage() {
  const [slot, setSlot] = useState('b_95')
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function handleSearch() {
    if (!slot.trim()) return
    setBusy(true)
    setError(null)
    try {
      const d = await slotWords(slot.trim())
      if (d.ok === false) {
        setError(d.error || '格式非法')
        setData(null)
      } else {
        setData(d)
      }
    } catch (e) {
      setError(e.message)
      setData(null)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="sid-search-page">
      <section className="publish-form">
        <h3 className="publish-title">词表槽位关键词</h3>
        <p className="publish-hint">
          输入一段 SID 槽位，如 <code>a_100</code> 或 <code>{'<b_95>'}</code>，
          显示该槽对应的全部词表关键词。
        </p>
        <div className="search-bar" style={{ padding: '10px' }}>
          <input
            value={slot}
            placeholder="a_100"
            onChange={(e) => setSlot(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          />
          <button onClick={handleSearch} disabled={busy || !slot.trim()}>
            {busy ? '查询中…' : '查询'}
          </button>
        </div>
      </section>

      {error && <div className="error-banner">{error}</div>}
      {busy && <div className="loading">查询中…</div>}

      {data && (
        <section className="debug-panel">
          <div className="sid-line">
            <code className="sid">{data.pos}_{data.idx}</code>
            <span className="fill-badge">{POS_NAMES[data.pos]}</span>
            {data.reserved && <span className="reserved-badge">保留槽</span>}
            <span className="fill-badge">{data.word_count} 个关键词</span>
          </div>
          {data.empty_slot ? (
            <div className="attr-row muted" style={{ marginTop: 10 }}>
              索引 0 = 空位槽：该位置无属性词（检索时通配）
            </div>
          ) : data.words.length === 0 ? (
            <div className="attr-row muted" style={{ marginTop: 10 }}>
              该槽暂无绑定词
            </div>
          ) : (
            <div className="slot-word-list">
              {data.words.map((w) => (
                <span key={w} className="slot-word-chip">{w}</span>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  )
}
