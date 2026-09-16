import { useEffect, useState } from 'react'
import ItemCard from './ItemCard.jsx'
import { fetchSearch, reservedPreview, reservedBind, reservedList, reservedRemove } from '../api.js'
import { POS_NAMES, POS_ORDER, reservedRange, RESERVED_PER_POS } from '../constants.js'

// 保留槽区间按位置动态计算：reservedRange(pos) = (sid_max[pos], sid_max[pos]+30]

/**
 * reserved-slot 旁路（OneRetrieval §3.2 / §3.4.4）：
 * 运营手动指定一个保留槽（所选位置的 (sid_max, sid_max+30] 内空闲号），把新趋势词
 * 绑定到该槽，并把该槽绑定到目标商品集合（可同时创建新商品 + 绑定已有商品）。
 * 此后任何含该词的查询确定性命中绑定商品，无需重训模型。仅内存，重启丢失。
 */
export default function PublishPage() {
  const [word, setWord] = useState('')
  const [pos, setPos] = useState('b')
  const [slotInput, setSlotInput] = useState('')
  const [preview, setPreview] = useState(null)
  // 绑定新商品只需填商品标题；品牌/卖家/类目由后端按标题解析或留空
  const [newItem, setNewItem] = useState({ item_title: '' })
  const [itemIds, setItemIds] = useState('')
  const [result, setResult] = useState(null)
  const [verify, setVerify] = useState(null)
  const [bindings, setBindings] = useState([])
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  // 所选位置的保留槽区间（随位置切换变化）
  const range = reservedRange(pos)

  // 进入页面即加载已注入列表
  useEffect(() => {
    reservedList().then((r) => setBindings(r.bindings)).catch(() => {})
  }, [])

  function setField(key, value) {
    setNewItem((f) => ({ ...f, [key]: value }))
  }

  function slotError() {
    const n = Number(slotInput)
    const { min, max } = reservedRange(pos)
    if (!slotInput.trim()) return '请指定槽号'
    if (!Number.isInteger(n) || n < min || n > max) {
      return `槽号需为整数，${pos} 位置范围 ${min}–${max}`
    }
    return null
  }

  async function handlePreview() {
    const err = slotError()
    if (!word.trim() || err) {
      setError(err || '请填写新词')
      setPreview(null)
      return
    }
    setBusy(true)
    setError(null)
    try {
      const r = await reservedPreview(word.trim(), pos, Number(slotInput))
      if (!r.ok) {
        setError(r.error || '预览失败')
        setPreview(null)
        return
      }
      setPreview(r)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  function injectPayload() {
    return { word: word.trim(), pos, slot: Number(slotInput) }
  }

  async function runInject(payload) {
    setBusy(true)
    setError(null)
    try {
      const r = await reservedBind(payload)
      if (!r.ok) {
        setError(r.error || '注入失败')
        return
      }
      setResult(r)
      const v = await fetchSearch(word.trim(), 3)
      setVerify(v)
      const list = await reservedList()
      setBindings(list.bindings)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  function checkSlot() {
    const err = slotError()
    if (!word.trim() || err) {
      setError(err || '请填写新词')
      return false
    }
    return true
  }

  /** 绑定新商品：必填标题 → 创建新商品并绑定到槽 */
  function handleInjectNew() {
    if (!checkSlot()) return
    if (!newItem.item_title.trim()) {
      setError('绑定新商品必须填写商品标题')
      return
    }
    runInject({ ...injectPayload(), new_item: newItem })
  }

  /** 绑定已有商品：必填 item id → 绑定到槽 */
  function handleInjectExisting() {
    if (!checkSlot()) return
    const ids = itemIds.split(',').map((s) => s.trim()).filter(Boolean).map(Number)
    if (!ids.length) {
      setError('绑定已有商品必须填写商品 id')
      return
    }
    runInject({ ...injectPayload(), item_ids: ids })
  }

  /** 已注入词：仅迁移槽位，不追加商品（原绑定商品随迁） */
  function handleInjectMove() {
    if (!checkSlot()) return
    runInject(injectPayload())
  }

  /** 删除已注入词：释放槽位，删除该词创建的商品，解绑已有商品 */
  async function handleRemoveWord(w) {
    if (!window.confirm(
      `删除已注入词「${w}」？\n将释放该词占用的保留槽，删除它创建的商品，已绑定的已有商品仅解绑（不删除）。`
    )) return
    setBusy(true)
    setError(null)
    try {
      const r = await reservedRemove(w)
      if (!r.ok) { setError(r.error || '删除失败'); return }
      // 表单若正载入该词则清空，避免残留槽号
      if (word.trim().toLowerCase() === w) {
        setPreview(null)
        setResult(null)
      }
      const list = await reservedList()
      setBindings(list.bindings)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const boundHits = result && verify
    ? verify.items.filter((i) =>
        (result.bound_ids || []).some((id) => String(id) === String(i.item_id))).length
    : 0

  return (
    <div className="publish-page">
      {/* 区块1：注入新词 */}
      <section className="publish-form">
        <h3 className="publish-title">reserved-slot 旁路：注入新词</h3>
        <p className="publish-hint">
          论文 §3.2 / §3.4.4：运营手动指定一个空闲保留槽
          （<code>{range.min}–{range.max}</code>，随位置变化），把新趋势词绑定到该槽，并绑定目标商品。
          此后含该词的查询确定性命中绑定商品，无需重训模型。
        </p>
        <label className="p-field">
          <span className="p-label">新词 <b>*</b></span>
          <input
            value={word}
            placeholder="如 LABUBU"
            onChange={(e) => { setWord(e.target.value); setPreview(null); setResult(null) }}
          />
        </label>
        <label className="p-field">
          <span className="p-label">所属位置</span>
          <select
            className="p-select"
            value={pos}
            onChange={(e) => { setPos(e.target.value); setPreview(null) }}
          >
            {POS_ORDER.map((p) => (
              <option key={p} value={p}>{p} · {POS_NAMES[p]}</option>
            ))}
          </select>
        </label>
        <label className="p-field">
          <span className="p-label">指定槽号 <b>*</b></span>
          <input
            type="number"
            min={range.min}
            max={range.max}
            value={slotInput}
            placeholder={`${range.min}–${range.max} 内空闲号（已注入列表可查占用）`}
            onChange={(e) => { setSlotInput(e.target.value); setPreview(null) }}
          />
        </label>
        <div className="p-actions">
          <button className="route-btn" onClick={handlePreview} disabled={busy || !word.trim() || !slotInput.trim()}>
            {busy ? '处理中…' : '预览槽位'}
          </button>
        </div>
      </section>

      {error && <div className="error-banner">{error}</div>}

      {/* 预览结果 */}
      {preview && (
        <section className="publish-form">
          <div className="publish-hint">
            {preview.existing && preview.moved_from ? (
              <>
                校验通过，将<strong>迁移槽位</strong>：
                <code>{preview.pos}_{preview.moved_from}</code> → <code>{preview.pos}_{preview.slot}</code>
                （<code>{preview.reserved_sid}</code>）· 原绑定商品一并迁移，可再追加商品
              </>
            ) : preview.existing ? (
              <>
                校验通过，沿用槽位 <code>{preview.reserved_sid}</code>
                <span className="publish-warn">（该词此前已注入，追加绑定商品）</span>
              </>
            ) : (
              <>
                校验通过，将绑定保留槽：<code>{preview.reserved_sid}</code>
              </>
            )}
          </div>
        </section>
      )}

      {/* 区块2：绑定商品 —— 新商品 / 已有商品 两个独立卡片 */}
      <section className="publish-form">
        <h4 className="publish-title">绑定新商品</h4>
        <label className="p-field">
          <span className="p-label">商品标题 <b>*</b></span>
          <input
            value={newItem.item_title}
            placeholder="必填，如 LABUBU 盲盒 联名款"
            onChange={(e) => setField('item_title', e.target.value)}
          />
        </label>
        <div className="p-actions">
          <button
            className="route-btn"
            onClick={handleInjectNew}
            disabled={busy || !preview || !newItem.item_title.trim()}
          >
            {busy ? '处理中…' : '创建并绑定'}
          </button>
        </div>
      </section>

      <section className="publish-form">
        <h4 className="publish-title">绑定已有商品</h4>
        <label className="p-field">
          <span className="p-label">商品 id <b>*</b></span>
          <input
            value={itemIds}
            placeholder="必填，逗号分隔多个 item_id，绑定到该槽"
            onChange={(e) => setItemIds(e.target.value)}
          />
        </label>
        <div className="p-actions">
          <button
            className="route-btn"
            onClick={handleInjectExisting}
            disabled={busy || !preview || !itemIds.trim()}
          >
            {busy ? '处理中…' : '绑定已有商品'}
          </button>
        </div>
      </section>

      {/* 已注入词：仅迁移槽位（不追加商品） */}
      {preview && preview.existing && preview.moved_from && (
        <section className="publish-form">
          <div className="publish-hint">
            不追加商品，仅把该词的绑定迁移到新槽（原绑定商品随迁）
          </div>
          <div className="p-actions">
            <button className="route-btn" onClick={handleInjectMove} disabled={busy}>
              {busy ? '处理中…' : `仅迁移槽位 ${preview.pos}_${preview.moved_from} → ${preview.pos}_${preview.slot}`}
            </button>
          </div>
        </section>
      )}

      {/* 区块3：结果 */}
      {result && (
        <section className="publish-result">
          <h4>注入成功</h4>
          <div className="publish-status">
            {result.word} → {result.pos}_{result.slot}（{result.reserved_sid}）· 绑定 {result.bound_count} 件 · 仅内存，重启丢失
          </div>
          {result.moved_from != null && result.moved_from !== result.slot && (
            <div className="publish-verify">
              槽位迁移：{result.pos}_{result.moved_from} → {result.pos}_{result.slot}
              {result.added_ids?.length ? ` · 本次新增绑定 ${result.added_ids.length} 件` : ' · 未追加商品'}
            </div>
          )}
          {result.created_item && (
            <div className="item-grid">
              <ItemCard item={result.created_item} rank={1} />
            </div>
          )}
          {result.bound_ids?.length > 0 && (
            <div className="publish-verify">
              绑定商品 id：{result.bound_ids.join('、')}
            </div>
          )}
          {verify && (
            <div className="publish-verify">
              检索验证「{word}」：共 {verify.items_found} 件 · 绑定商品命中 {boundHits}/{result.bound_count}
              {boundHits > 0 ? ' ✓' : ' ✗（未命中）'}
            </div>
          )}
        </section>
      )}

      {/* 区块4：已注入列表 */}
      {bindings.length > 0 && (
        <section className="publish-form">
          <h4 className="publish-title">已注入 {bindings.length} 个词（每位置各含 {RESERVED_PER_POS} 个保留槽）</h4>
          <p className="publish-hint">点击词条载入表单，可修改槽位或追加绑定商品</p>
          <div className="bind-list">
            {bindings.map((b) => (
              <div
                className="bind-item"
                key={b.pos + b.slot}
                title={`载入「${b.word}」到表单以修改槽位`}
                onClick={() => {
                  setWord(b.word)
                  setPos(b.pos)
                  setSlotInput(String(b.slot))
                  setPreview(null)
                  setResult(null)
                }}
              >
                <code>{b.pos}_{b.slot}</code> · {b.word} · 绑定 {b.bound_count} 件
                <button
                  className="toggle-btn remove"
                  disabled={busy}
                  onClick={(e) => { e.stopPropagation(); handleRemoveWord(b.word) }}
                >
                  删除
                </button>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}
