import { Link, useParams } from 'react-router-dom'
import { useRun } from '../api/queries'
import { isActiveRun } from '../api/runs'
import { ResourceState } from '../components/ResourceState'
import { formatTime } from '../api/time'
import { StatusBadge } from '../components/StatusBadge'

export function RunPage() {
  const { jobId = '' } = useParams()
  const query = useRun(jobId)
  const run = query.data
  return (
    <>
      <Link className="back-link" to="/runs">← All runs</Link>
      <ResourceState loading={query.isPending} error={query.error} retry={() => void query.refetch()} />
      {run && (
        <>
          <header className="page-heading">
            <div><p className="eyebrow">RUN / EXECUTION STATE</p><h1>{run.name}</h1><code className="muted">{run.id}</code></div>
            <div className="run-heading-status">
              <StatusBadge status={run.status} />
              {isActiveRun(run.status) && <span className="refresh-indicator" role="status"><i aria-hidden="true" />{query.streamStatus === 'live' ? 'Live' : query.streamStatus === 'fallback' ? 'Fallback polling · 5s / reconnecting' : 'Connecting live updates…'}</span>}
              <button onClick={() => void query.refetch()} disabled={query.isFetching}>Refresh</button>
            </div>
          </header>
          <div className="stats">
            <div><span>Tasks complete</span><strong>{run.tasks.filter(task => task.status === 'COMPLETE').length}<small> / {run.tasks.length}</small></strong></div>
            <div><span>Recorded retry counter total</span><strong>{run.tasks.reduce((sum, task) => sum + task.retryCount, 0)}</strong></div>
            <div><span>Dead-lettered tasks</span><strong>{run.tasks.filter(task => task.status === 'DEAD_LETTERED').length}</strong></div>
            <div><span>Failure policy</span><strong className="compact">{run.failurePolicy}</strong></div>
          </div>
          <div className="run-times">
            <span>Created <strong>{formatTime(run.createdAt)}</strong></span>
            <span>Last updated <strong>{formatTime(run.updatedAt)}</strong></span>
          </div>
          <section className="panel">
            <div className="panel-heading"><h2>Task execution state</h2><span className="muted">{run.tasks.length} tasks</span></div>
            <div className="table-scroll">
              <table>
                <thead><tr><th>Task</th><th>Capability</th><th>Status</th><th>Retries / max</th><th>Created</th><th>Last updated</th></tr></thead>
                <tbody>{run.tasks.map(task => (
                  <tr key={task.id}>
                    <td>{task.name}<code className="sub-id">{task.id}</code></td>
                    <td><span className="type-label">{task.type}</span></td>
                    <td><StatusBadge status={task.status} /></td>
                    <td><span className="number">{task.retryCount}</span><span className="muted"> / {task.maxRetries}</span></td>
                    <td className="muted nowrap">{formatTime(task.createdAt)}</td>
                    <td className="muted nowrap">{formatTime(task.updatedAt)}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
            {!run.tasks.length && <div className="resource-state">No tasks recorded.</div>}
          </section>
          <p className="footnote">Timestamps show record creation and last state updates, not active attempt duration. Attempt history and task outputs are not exposed in this view.</p>
        </>
      )}
    </>
  )
}
