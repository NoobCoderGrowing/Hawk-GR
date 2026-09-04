import { useState } from 'react'

const K_OPTIONS = [1, 3, 5]

export default function SearchBar({ onSearch, k, onKChange, disabled }) {
  const [query, setQuery] = useState('')

  function submit() {
    if (query.trim() && !disabled) onSearch(query, k)
  }

  return (
    <div className="search-bar">
      <input
        type="text"
        value={query}
        placeholder="输入商品查询，如 网球拍"
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
        disabled={disabled}
      />
      <label className="k-label">
        k
        <select value={k} onChange={(e) => onKChange(Number(e.target.value))} disabled={disabled}>
          {K_OPTIONS.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </select>
      </label>
      <button onClick={submit} disabled={disabled || !query.trim()}>
        搜索
      </button>
    </div>
  )
}
