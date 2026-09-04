import ItemCard from './ItemCard.jsx'

const MAX_RENDER = 500 // 与后端 MAX_RESULTS 对齐；全通配模式全库命中时也截断到 500

export default function ResultList({ items }) {
  const list = Array.isArray(items) ? items : []
  const shown = list.slice(0, MAX_RENDER)

  return (
    <section className="results">
      <div className="results-header">
        {list.length > 0 ? `找到 ${list.length} 件商品` : '无结果'}
      </div>
      {list.length === 0 ? (
        <div className="empty-state">
          没有找到匹配的商品。试试换个查询词或增大 k 值。
        </div>
      ) : (
        <div className="item-grid">
          {shown.map((item, i) => (
            <ItemCard key={item.item_id ?? i} item={item} rank={i + 1} />
          ))}
        </div>
      )}
      {list.length > shown.length && (
        <div className="results-header" style={{ marginTop: 8 }}>
          仅显示前 {shown.length} 件（共 {list.length} 件）
        </div>
      )}
    </section>
  )
}
