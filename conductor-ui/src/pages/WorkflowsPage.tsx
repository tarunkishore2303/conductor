import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getWorkflows } from '../api/workflows'
import { ResourceState } from '../components/ResourceState'
import { formatTime } from '../api/time'

export function WorkflowsPage() {
  const query = useQuery({ queryKey: ['workflows'], queryFn: ({ signal }) => getWorkflows(signal) })
  return (
    <>
      <header className="page-heading">
        <div><p className="eyebrow">ORCHESTRATION / DEFINITIONS</p><h1>Workflows</h1><p>Deterministic DAGs. Distributed execution.</p></div>
        <button onClick={() => void query.refetch()} disabled={query.isFetching}>Refresh</button>
      </header>
      <ResourceState loading={query.isPending} error={query.error} retry={() => void query.refetch()} />
      {query.data && (
        <section className="panel">
          <div className="panel-heading"><h2>Workflow registry</h2><span className="muted">{query.data.length} recent definitions · up to 50</span></div>
          {!query.data.length ? (
            <div className="resource-state"><strong>No workflows yet</strong><p>Create a definition through the Conductor API to see it here.</p></div>
          ) : (
            <div className="table-scroll">
              <table>
                <thead><tr><th>Workflow</th><th>Tasks</th><th>Failure policy</th><th>Created</th><th><span className="sr-only">Details</span></th></tr></thead>
                <tbody>{query.data.map(workflow => (
                  <tr key={workflow.id}>
                    <td><Link className="entity-link" to={`/workflows/${workflow.id}`}>{workflow.name}</Link><code className="sub-id">{workflow.id}</code></td>
                    <td><span className="number">{workflow.tasks.length}</span></td>
                    <td><span className="type-label">{workflow.defaultFailurePolicy}</span></td>
                    <td className="muted nowrap">{formatTime(workflow.createdAt)}</td>
                    <td><Link aria-label={`View ${workflow.name}`} className="row-arrow" to={`/workflows/${workflow.id}`}>↗</Link></td>
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
