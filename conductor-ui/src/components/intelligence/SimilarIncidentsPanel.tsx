import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getSimilarIncidents } from '../../api/intelligence'
import { formatTime } from '../../api/time'
import type { Run } from '../../api/types'
import { IntelligenceError } from './shared'
import { snapshotOptions } from './queries'

export function SimilarIncidentsPanel({ run }: { run: Run }) {
  const query = useQuery({ queryKey: ['similar-incidents', run.id, 3], queryFn: () => getSimilarIncidents(run.id, 3), enabled: false, ...snapshotOptions })
  const matches = query.data?.matches.filter(match => match.jobId !== run.id)
  return <section className="panel intel-panel" aria-labelledby="incidents-heading">
    <div className="panel-heading"><h2 id="incidents-heading">Similar Incidents</h2><span className="muted">Persisted vector evidence</span></div>
    <div className="intel-body">
      {run.status !== 'FAILED' ? <p>Historical incident search is available for failed runs with indexed analysis.</p> : <>
        <p className="intel-note">Search uses existing local embeddings. Similarity is a retrieval score, not a probability.</p>
        <button disabled={query.isFetching} onClick={() => void query.refetch()}>{query.isFetching ? 'Searching…' : 'Find similar incidents'}</button>
        {query.error && <IntelligenceError error={query.error} operation="search" />}
        {matches && !matches.length && <p className="intel-empty">No sufficiently similar historical incidents were found.</p>}
        {matches && matches.length > 0 && <div className="intel-matches">{matches.map(match => <article className="intel-match" key={match.incidentId}>
          <div className="intel-match-heading"><h3>{match.taskName}</h3><span className="type-label" title="Cosine similarity is a retrieval score, not a probability.">Similarity {match.similarity.toFixed(3)}</span></div>
          <dl className="intel-facts"><div><dt>Historical run</dt><dd><Link to={`/runs/${match.jobId}`}><code>{match.jobId}</code></Link></dd></div><div><dt>Task</dt><dd><code>{match.taskId}</code></dd></div><div><dt>Analysis</dt><dd><code>{match.analysisId}</code></dd></div><div><dt>Failure recorded</dt><dd>{match.failureTimestamp ? formatTime(match.failureTimestamp) : 'Unavailable'}</dd></div></dl>
        </article>)}</div>}
      </>}
    </div>
  </section>
}
