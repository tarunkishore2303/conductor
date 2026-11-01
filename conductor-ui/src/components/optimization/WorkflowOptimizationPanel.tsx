import { Link } from 'react-router-dom'
import { generateWorkflowOptimization, getWorkflowOptimization, optimizationError } from '../../api/optimization'
import { useStoredIntelligence } from '../intelligence/queries'
import { EvidenceLimits, SnapshotMetadata } from '../intelligence/shared'
import { OptimizationCandidate } from './OptimizationCandidate'

export function WorkflowOptimizationPanel({ workflowId }: { workflowId: string }) {
  const { query, generation, requestGeneration } = useStoredIntelligence(['optimization', workflowId], true,
    signal => getWorkflowOptimization(workflowId, signal), () => generateWorkflowOptimization(workflowId))
  const report = query.data
  return <section className="panel intel-panel" aria-labelledby="optimization-heading">
    <div className="panel-heading"><h2 id="optimization-heading">Optimization Intelligence</h2><span className="read-only">ADVISORY ONLY</span></div>
    <div className="intel-body">
      <p className="intel-note">Recommendations are informational. Conductor does not automatically rewrite workflows or control execution.</p>
      {query.isPending ? <p role="status">Loading stored optimization report…</p>
        : query.error ? <div role="alert" className="intel-error"><p>{optimizationError(query.error)}</p><button onClick={() => void query.refetch()}>Try again</button></div>
          : report ? <>
            <div className="opt-evidence"><h3>Deterministic analyzer evidence — Conductor</h3>
              <dl className="intel-facts">
                <div><dt>Linked settled runs analyzed</dt><dd>{report.facts.runsConsidered}</dd></div>
                <div><dt>Minimum sample size</dt><dd>{report.facts.minimumSampleSize}</dd></div>
                <div><dt>History window</dt><dd>{report.facts.historyWindowSeconds.toLocaleString()} seconds</dd></div>
                <div><dt>History truncated</dt><dd>{report.facts.historyTruncated ? 'Yes' : 'No'}</dd></div>
                <div><dt>Excluded runs</dt><dd>{report.facts.excludedRuns}</dd></div>
                <div><dt>Candidates</dt><dd>{report.facts.candidates.length}</dd></div>
              </dl>
              <p className="intel-note">Measurements describe bounded recorded executions, not global statistical certainty. Rates are observed execution frequencies, not probabilities. Duration is recorded active attempt time, not workflow wall-clock contribution.</p>
              {report.facts.outcome === 'INSUFFICIENT_HISTORY' && <p className="intel-empty">Insufficient settled linked execution history: {report.facts.runsConsidered} runs; minimum sample size is {report.facts.minimumSampleSize}.</p>}
              {report.facts.outcome === 'NO_CANDIDATES' && <p className="intel-empty">No optimization candidates were identified from the bounded execution history.</p>}
              {report.facts.candidates.map(candidate => <OptimizationCandidate key={candidate.candidateId} candidate={candidate} />)}
              <EvidenceLimits notes={report.facts.limitations} />
              {report.facts.runIds.length > 0 && <details className="intel-limits"><summary>Analyzed run references</summary><ul>{report.facts.runIds.map(id => <li key={id}><Link to={`/runs/${id}`}><code>{id}</code></Link></li>)}</ul></details>}
            </div>
            <div className="opt-interpretation"><h3>{report.model === 'deterministic' ? 'Deterministic Conductor notice' : 'Local Ollama interpretation'}</h3><p>{report.interpretation.overview}</p>
              {report.interpretation.recommendations.map((recommendation, index) => <div className="intel-task" key={index}><h4>Candidate references: {recommendation.candidateIds.join(', ')}</h4><p>{recommendation.recommendation}</p><p className="intel-note">{recommendation.rationale}</p></div>)}
            </div>
            <SnapshotMetadata id={report.optimizationId} model={report.model} generatedAt={report.generatedAt} />
            <details className="intel-limits"><summary>Snapshot fingerprint</summary><p>Version {report.optimizationVersion}</p><code className="opt-hash">{report.contextHash}</code></details>
          </> : <div className="intel-empty"><p>No optimization report generated yet.</p><button disabled={generation.isPending} onClick={requestGeneration}>{generation.isPending ? 'Generating…' : 'Generate optimization report'}</button></div>}
      {generation.error && <div role="alert" className="intel-error">{optimizationError(generation.error, true)}</div>}
    </div>
  </section>
}
