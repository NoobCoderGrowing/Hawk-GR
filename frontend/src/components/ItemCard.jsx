const HIDE_BRANDS = new Set(['无品牌', '其他/other', ''])

export default function ItemCard({ item, rank }) {
  const brand = HIDE_BRANDS.has(item.brand_name) ? '' : item.brand_name
  const title = item.item_title ?? ''
  const cat = item.category_level1_name ?? ''
  const cat2 = item.category_level2_name ?? ''
  const cat3 = item.category_level3_name ?? ''

  return (
    <div className="item-card">
      <span className="rank">{rank}</span>
      <div className="card-body">
        <div className="title">
          {brand && <span className="brand">{brand}</span>}
          {title}
        </div>
        <div className="cats">
          {cat && <span className="cat-l1">{cat}</span>}
          {cat2 && cat2 !== 'UNKNOWN' && <span className="cat-l2">{cat2}</span>}
          {cat3 && cat3 !== 'UNKNOWN' && <span className="cat-l3">{cat3}</span>}
        </div>
        <div className="item-meta">
          <span className="item-id">id：{String(item.item_id)}</span>
          <code className="item-sid">{item.matched_sid ?? item.item_sid ?? ''}</code>
        </div>
        {item.matched_sid && item.item_sid && item.matched_sid !== item.item_sid && (
          <div className="item-real-sid">原 SID：<code>{item.item_sid}</code></div>
        )}
      </div>
    </div>
  )
}
