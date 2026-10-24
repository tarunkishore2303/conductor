import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { getWorkflow } from '../api/workflows'
import { ResourceState } from '../components/ResourceState'
import { formatTime } from '../api/time'
import { lazy, Suspense } from 'react'

const WorkflowDag = lazy(() => import('../components/WorkflowDag').then(module => ({ default: module.WorkflowDag })))

export function WorkflowPage() {
  const { workflowId = '' } = useParams()
  const query = useQuery({ queryKey: ['workflow', workflowId], queryFn: ({ signal }) => getWorkflow(workflowId, signal) })
  const workflow = query.data
  return (
    <>
      <Link className="back-link" to="/workflows">← All workflows</Link>
      <ResourceState loading={query.isPending} error={query.error} retry={() => void query.refetch()} />
      {workflow && (
        <>
          <header className="page-heading">
            <div><p className="eyebrow">WORKFLOW DEFINITION</p><h1>{workflow.name}</h1><code className="muted">{workflow.id}</code></div>
            <span className="read-only">READ ONLY</span>
          </header>
          {workflow.description && <p className="description">{workflow.description}</p>}
          <div className="stats">
            <div><span>Task definitions</span><strong>{workflow.tasks.length}</strong></div>
            <div><span>Failure policy</span><strong className="compact">{workflow.defaultFailurePolicy}</strong></div>
            <div><span>Created</span><strong className="compact">{formatTime(workflow.createdAt)}</strong></div>
          </div>
          <section className="panel">
            <div className="panel-heading"><h2>Dependency graph</h2><span className="muted">Left to right · pan & zoom to explore</span></div>
            <Suspense fallback={<div className="resource-state" role="status">Loading dependency graph…</div>}><WorkflowDag tasks={workflow.tasks} /></Suspense>
          </section>
          <section className="panel">
            <div className="panel-heading"><h2>Task definitions</h2><span className="muted">Dependency order is enforced by Conductor</span></div>
            <div className="table-scroll">
              <table>
                <thead><tr><th>Task / logical ID</th><th>Capability</th><th>Direct dependencies</th><th>Max retries</th></tr></thead>
                <tbody>{workflow.tasks.map(task => (
                  <tr key={task.taskId}>
                    <td>{task.name}<code className="sub-id">{task.taskId}</code></td>
                    <td><span className="type-label">{task.type}</span></td>
                    <td><code className="muted">{task.dependsOn.join(', ') || '—'}</code></td>
                    <td>{task.maxRetries}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </>
  )
}
