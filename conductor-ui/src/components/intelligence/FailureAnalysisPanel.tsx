import { generateFailureAnalysis, getFailureAnalysis } from '../../api/intelligence'
import { formatTime } from '../../api/time'
import type { Run } from '../../api/types'
import { EvidenceLimits, IntelligenceError, SnapshotMetadata, TextList } from './shared'
import { useStoredIntelligence } from './queries'

export function FailureAnalysisPanel({ run }: { run: Run }) {
  const applicable = run.status === 'FAILED'
  const settled = applicable && !run.tasks.some(task => task.status === 'RUNNING' || (task.status === 'PENDING' && task.retryCount > 0))
  const { query, generation, requestGeneration } = useStoredIntelligence(['failure-analysis', run.id], applicable,
    signal => getFailureAnalysis(run.id, signal), () => generateFailureAnalysis(run.id))
  const analysis = query.data
  return <section className="panel intel-panel" aria-labelledby="failure-heading">
    <div className="panel-heading"><h2 id="failure-heading">Failure Analysis</h2><span className="muted">Evidence and interpretation</span></div>
    <div className="intel-body">
      {!applicable ? <p>Failure analysis is only available for failed runs.</p>
        : query.isPending ? <p role="status">Loading stored failure analysis…</p>
          : query.error ? <IntelligenceError error={query.error} operation="read" retry={() => void query.refetch()} />
            : analysis ? <>
              <div className="intel-columns">
                <div className="intel-evidence"><h3>Evidence — Conductor</h3>
                  {analysis.facts.tasks.map(task => <div className="intel-task" key={task.taskId}>
                    <h4>{task.name}</h4><code className="sub-id">{task.taskId}</code>
                    <dl className="intel-facts"><div><dt>Recorded state</dt><dd>{task.status}</dd></div><div><dt>Recorded attempts</dt><dd>{task.attempts.length}</dd></div><div><dt>Maximum retries</dt><dd>{task.maxRetries}</dd></div><div><dt>DLQ recorded</dt><dd>{task.deadLetteredAt ? formatTime(task.deadLetteredAt) : 'Not recorded'}</dd></div></dl>
                    {task.attempts.length > 0 && <div className="table-scroll"><table><thead><tr><th>Attempt</th><th>Status / error type</th><th>Completed / retry scheduled</th></tr></thead><tbody>{task.attempts.map((attempt, index) => <tr key={index}>
                      <td>{attempt.attemptNumber === null ? 'Unknown' : attempt.attemptNumber}</td><td>{attempt.status}{attempt.errorType && <code className="sub-id">{attempt.errorType}</code>}</td>
                      <td>{attempt.completedAt ? formatTime(attempt.completedAt) : 'Not recorded'}{attempt.retryScheduledAt && <span className="intel-retry">Retry: {formatTime(attempt.retryScheduledAt)}</span>}</td>
                    </tr>)}</tbody></table></div>}
                  </div>)}<EvidenceLimits notes={analysis.facts.unavailableEvidence} />
                </div>
                <div className="intel-interpretation"><h3>Local Ollama interpretation</h3><h4>Likely cause</h4><p>{analysis.interpretation.likelyCause}</p><h4>Model confidence</h4><p>{analysis.interpretation.confidence}</p><p className="intel-note">A model assessment, not a calibrated probability.</p><h4>Explanation</h4><p>{analysis.interpretation.explanation}</p><h4>Recommended actions</h4><TextList items={analysis.interpretation.recommendedActions} /></div>
              </div><SnapshotMetadata id={analysis.analysisId} model={analysis.model} generatedAt={analysis.generatedAt} />
            </> : <div className="intel-empty"><p>No failure analysis generated yet.</p>{!settled && <p className="intel-note">Wait for execution and retries to settle before analyzing.</p>}<button disabled={!settled || generation.isPending} onClick={requestGeneration}>{generation.isPending ? 'Analyzing…' : 'Analyze failure'}</button></div>}
      {generation.error && <IntelligenceError error={generation.error} operation="generation" />}
    </div>
  </section>
}
