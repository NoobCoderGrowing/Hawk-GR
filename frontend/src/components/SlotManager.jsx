import { useEffect, useState } from 'react'
import ItemCard from './ItemCard.jsx'
import { slotAdd, slotRemove, slotList, fetchSearch } from '../api.js'

/**
 * 坑位系统：在某个关键词下固定商品的搜索排序位。
 * 只需设置 商品ID + 排序号 + 关键词。包含匹配（query 含关键词即生效，
 * 多个命中取最长）；商品不在自然结果中也强制插入到该位（广告坑位语义）。
 * 仅内存，重启丢失。
 */
export default function SlotManager() {
  const [keyword, setKeyword] = useState('')
  const [itemId, setItemId] = useState('')
  const [rank, setRank] = useState('')
  const [slots, setSlots] = useState([])
  const [verify, setVerify] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    slotList().then((r) => setSlots(r.slots)).catch(() => {})
  }, [])

  async function refreshList() {
    const r = await slotList()
    setSlots(r.slots)
  }

  async function handleAdd() {
    const k = keyword.trim()
    const id = Number(itemId)
    const rk = Number(rank)
    if (!k) { setError('请填写关键词'); return }
    if (!itemId.trim() || !Number.isInteger(id)) { setError('请填写有效的商品 id'); return }
    if (!rank.trim() || !Number.isInteger(rk) || rk < 1) { setError('排序号必须为 ≥1 的整数'); return }
    setBusy(true)
    setError(null)
    try {
      const r = await slotAdd(k, id, rk)
      if (!r.ok) { setError(r.error || '设置失败'); return }
      setKeyword('')
      setItemId('')
      setRank('')
      await refreshList()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleRemove(k, rk) {
    setBusy(true)
    setError(null)
    try {
      const r = await slotRemove(k, rk)
      if (!r.ok) { setError(r.error || '移除失败'); return }
      await refreshList()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleVerify(k) {
    setBusy(true)
    setError(null)
    try {
      const r = await fetchSearch(k, 3)
      setVerify({ keyword: k, result: r })
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="publish-page">
      {/* 添加坑位 */}
      <section className="publish-form">
        <h3 className="publish-title">坑位系统：关键词下固定商品排序</h3>
        <p className="publish-hint">
          设置 商品ID + 排序号 + 关键词，即可在搜索该关键词时把商品放到指定排序位。
          包含匹配，商品不在自然结果中也强制插入到该位。
        </p>
        <label className="p-field">
          <span className="p-label">关键词 <b>*</b></span>
          <input
            value={keyword}
            placeholder="如 LABUBU / 手机"
            onChange={(e) => setKeyword(e.target.value)}
          />
        </label>
        <label className="p-field">
          <span className="p-label">商品 ID <b>*</b></span>
          <input
            value={itemId}
            placeholder="如 2925142"
            onChange={(e) => setItemId(e.target.value)}
          />
        </label>
        <label className="p-field">
          <span className="p-label">排序号 <b>*</b></span>
          <input
            type="number"
            min="1"
            value={rank}
            placeholder="如 1（第 1 位）"
            onChange={(e) => setRank(e.target.value)}
          />
        </label>
        <div className="p-actions">
          <button className="route-btn" onClick={handleAdd} disabled={busy}>
            {busy ? '处理中…' : '添加 / 更新坑位'}
          </button>
        </div>
      </section>

      {error && <div className="error-banner">{error}</div>}

      {/* 全部坑位 */}
      {slots.length > 0 && (
        <section className="publish-form">
          <h4 className="publish-title">全部坑位（{slots.length} 个关键词）</h4>
          <div className="bind-list">
            {slots.map((g) => (
              <div className="slot-group" key={g.keyword}>
                <div className="slot-group-head">
                  <code>{g.keyword}</code>
                  <button className="toggle-btn" onClick={() => handleVerify(g.keyword)} disabled={busy}>
                    验证检索
                  </button>
                </div>
                <div className="bind-list" style={{ marginTop: 6 }}>
                  {g.slots.map((s) => (
                    <div className="slot-row" key={s.rank}>
                      <span className="rank-chip">第 {s.rank} 位</span>
                      <span>商品 {s.item_id}</span>
                      <button
                        className="toggle-btn remove"
                        onClick={() => handleRemove(g.keyword, s.rank)}
                        disabled={busy}
                      >
                        移除
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* 验证检索 */}
      {verify && (
        <section className="publish-result">
          <h4>验证检索「{verify.keyword}」</h4>
          {verify.result.items?.length > 0 ? (
            <div className="item-grid">
              {verify.result.items.map((item, i) => (
                <ItemCard key={item.item_id ?? i} item={item} rank={i + 1} />
              ))}
            </div>
          ) : (
            <div className="empty-state">无结果</div>
          )}
        </section>
      )}
    </div>
  )
}
