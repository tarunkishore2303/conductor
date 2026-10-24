import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getRuns } from '../api/runs'
import { ResourceState } from '../components/ResourceState'
import { formatTime } from '../api/time'
import { StatusBadge } from '../components/StatusBadge'

export function RunsPage() {
  const query = useQuery({ queryKey: ['runs'], queryFn: ({ signal }) => getRuns(signal) })
  return (
    <>
      <header className="page-heading">
        <div><p className="eyebrow">ORCHESTRATION / EXECUTIONS</p><h1>Runs</h1><p>Inspect execution state across the worker fleet.</p></div>
        <button onClick={() => void query.refetch()} disabled={query.isFetching}>Refresh</button>
      </header>
      <ResourceState loading={query.isPending} error={query.error} retry={() => void query.refetch()} />
      {query.data && (
        <section className="panel">
          <div className="panel-heading"><h2>Recent runs</h2><span className="muted">Newest first · up to 50</span></div>
          {!query.data.length ? (
            <div className="resource-state"><strong>No runs yet</strong><p>Execute a workflow through the API to inspect its progress here.</p></div>
          ) : (
            <div className="table-scroll">
              <table>
                <thead><tr><th>Run</th><th>Status</th><th>Created</th><th>Last updated</th><th><span className="sr-only">Details</span></th></tr></thead>
                <tbody>{query.data.map(run => (
                  <tr key={run.id}>
                    <td><Link className="entity-link" to={`/runs/${run.id}`}>{run.name}</Link><code className="sub-id">{run.id}</code></td>
                    <td><StatusBadge status={run.status} /></td>
                    <td className="muted nowrap">{formatTime(run.createdAt)}</td>
                    <td className="muted nowrap">{formatTime(run.updatedAt)}</td>
                    <td><Link aria-label={`Inspect ${run.name}`} className="row-arrow" to={`/runs/${run.id}`}>↗</Link></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </>
  )
}
