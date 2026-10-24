import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { WorkflowsPage } from './pages/WorkflowsPage'
import { WorkflowPage } from './pages/WorkflowPage'
import { RunsPage } from './pages/RunsPage'
import { RunPage } from './pages/RunPage'

export default function App() {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <NavLink to="/workflows" className="brand">
          <span className="brand-mark" aria-hidden="true">C</span>
          <span>Conductor<small>CONTROL PLANE</small></span>
        </NavLink>
        <p className="nav-label">WORKSPACE</p>
        <nav aria-label="Primary navigation">
          <NavLink to="/workflows"><span aria-hidden="true">◇</span>Workflows</NavLink>
          <NavLink to="/runs"><span aria-hidden="true">▤</span>Runs</NavLink>
        </nav>
        <div className="sidebar-bottom">
          <span className="read-only">READ-ONLY MVP</span>
          <p>Visibility into your distributed workflow engine.</p>
        </div>
      </aside>
      <div className="workspace">
        <div className="topbar">
          <span>Distributed workflow orchestration</span>
          <span className="topbar-label">Conductor / Local control plane</span>
        </div>
        <main>
          <Routes>
            <Route path="/" element={<Navigate to="/workflows" replace />} />
            <Route path="/workflows" element={<WorkflowsPage />} />
            <Route path="/workflows/:workflowId" element={<WorkflowPage />} />
            <Route path="/runs" element={<RunsPage />} />
            <Route path="/runs/:jobId" element={<RunPage />} />
            <Route path="*" element={<div className="resource-state"><h1>Page not found</h1><NavLink to="/workflows">Return to workflows</NavLink></div>} />
          </Routes>
        </main>
        <footer><span>CONDUCTOR</span><span>AI advises. The engine decides.</span></footer>
      </div>
    </div>
  )
}
