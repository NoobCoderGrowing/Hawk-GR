import { useState } from 'react'
import SearchBar from './components/SearchBar.jsx'
import DebugPanel from './components/DebugPanel.jsx'
import ResultList from './components/ResultList.jsx'
import RouteEditor from './components/RouteEditor.jsx'
import PublishPage from './components/PublishPage.jsx'
import SlotManager from './components/SlotManager.jsx'
import SidSearchPage from './components/SidSearchPage.jsx'
import { fetchSearch, searchBySid } from './api.js'

export default function App() {
  const [view, setView] = useState('search')
  const [result, setResult] = useState(null)
  const [routedResult, setRoutedResult] = useState(null)
  const [k, setK] = useState(3)
  const [editMode, setEditMode] = useState(false)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [routeLoading, setRouteLoading] = useState(false)

  async function handleSearch(query, k) {
    if (!query.trim()) return
    setLoading(true)
    setError(null)
    try {
      const data = await fetchSearch(query, k)
      setResult(data)
      setRoutedResult(null)
      setEditMode(false)
    } catch (e) {
      setError(e.message)
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  async function handleRoute(sid) {
    setRouteLoading(true)
    setError(null)
    try {
      const data = await searchBySid(sid, k)
      setRoutedResult(data)
    } catch (e) {
      setError(e.message)
    } finally {
      setRouteLoading(false)
    }
  }

  // In edit mode show the manually-routed result once available, else the original.
  const active = routedResult || result

  return (
    <div className="app">
      <header className="app-header">
        <h1>Hawk-GR 商品检索</h1>
        <p className="subtitle">Query → SID → Items</p>
      </header>

      <nav className="nav">
        <button
          className={`nav-tab ${view === 'search' ? 'active' : ''}`}
          onClick={() => setView('search')}
        >
          检索
        </button>
        <button
          className={`nav-tab ${view === 'publish' ? 'active' : ''}`}
          onClick={() => setView('publish')}
        >
          注入新词
        </button>
        <button
          className={`nav-tab ${view === 'slots' ? 'active' : ''}`}
          onClick={() => setView('slots')}
        >
          坑位排序
        </button>
        <button
          className={`nav-tab ${view === 'sid' ? 'active' : ''}`}
          onClick={() => setView('sid')}
        >
          查词表
        </button>
      </nav>

      {/* 两个视图都保持挂载，用 CSS 隐藏不活动的那个 —— 这样切换 tab 时
          检索输入/结果/编辑路由状态、发布表单状态全部保留 */}
      <div className={view === 'search' ? 'app-view' : 'app-view view-hidden'}>
        <SearchBar onSearch={handleSearch} k={k} onKChange={setK} disabled={loading} />

        {error && <div className="error-banner">{error}</div>}
        {loading && <div className="loading">检索中…</div>}
        {routeLoading && <div className="loading">路由中…</div>}

        {result && (
          <>
            <div className="editor-toggle">
              <button
                className={editMode ? 'toggle-btn active' : 'toggle-btn'}
                onClick={() => setEditMode((v) => !v)}
              >
                {editMode ? '退出编辑路由' : '编辑路由'}
              </button>
            </div>

            {editMode ? (
              <>
                <RouteEditor sid={result.query_sid} onRoute={handleRoute} />
                {active && (
                  <>
                    <DebugPanel debug={active} />
                    <ResultList items={active.items} />
                  </>
                )}
              </>
            ) : (
              <>
                <DebugPanel debug={result} />
                <ResultList items={result.items} />
              </>
            )}
          </>
        )}

        {!result && !loading && !error && (
          <div className="welcome">
            输入查询词开始检索，例如：<code>网球拍</code>、<code>白色T恤</code>、<code>小米黑色手机壳</code>
          </div>
        )}
      </div>

      <div className={view === 'publish' ? 'app-view' : 'app-view view-hidden'}>
        <PublishPage />
      </div>

      <div className={view === 'slots' ? 'app-view' : 'app-view view-hidden'}>
        <SlotManager />
      </div>

      <div className={view === 'sid' ? 'app-view' : 'app-view view-hidden'}>
        <SidSearchPage />
      </div>
    </div>
  )
}
