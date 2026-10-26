import { generateExecutionSummary, getExecutionSummary } from '../../api/intelligence'
import type { Run } from '../../api/types'
import { EvidenceLimits, IntelligenceError, SnapshotMetadata, TextList } from './shared'
import { useStoredIntelligence } from './queries'

export function ExecutionSummaryPanel({ run }: { run: Run }) {
  const applicable = run.status === 'COMPLETE' || run.status === 'FAILED'
  const settled = applicable && !run.tasks.some(task => task.status === 'RUNNING' || (task.status === 'PENDING' && task.retryCount > 0))
    && (run.status !== 'COMPLETE' || run.tasks.every(task => task.status === 'COMPLETE'))
  const { query, generation, requestGeneration } = useStoredIntelligence(['summary', run.id], applicable,
    signal => getExecutionSummary(run.id, signal), () => generateExecutionSummary(run.id))
  const summary = query.data
  return <section className="panel intel-panel" aria-labelledby="summary-heading">
    <div className="panel-heading"><h2 id="summary-heading">Execution Summary</h2><span className="muted">Stored run snapshot</span></div>
    <div className="intel-body">
      {!applicable ? <p>Execution summaries are available for settled COMPLETE or FAILED runs.</p>
        : query.isPending ? <p role="status">Loading stored summary…</p>
          : query.error ? <IntelligenceError error={query.error} operation="read" retry={() => void query.refetch()} />
            : summary ? <>
              <div className="intel-columns">
                <div className="intel-evidence"><h3>Facts — Conductor</h3>
                  <dl className="intel-facts">
                    <div><dt>Tasks</dt><dd>{summary.facts.taskCount}</dd></div>
                    <div><dt>Completed</dt><dd>{summary.facts.completedTasks}</dd></div>
                    <div><dt>Failed</dt><dd>{summary.facts.failedTasks}</dd></div>
                    <div><dt>Dead-lettered</dt><dd>{summary.facts.deadLetteredTasks}</dd></div>
                    <div><dt>Cancelled</dt><dd>{summary.facts.cancelledTasks}</dd></div>
                    <div><dt>Blocked-pending records</dt><dd>{summary.facts.blockedPendingTasks}</dd></div>
                    <div><dt>Recorded attempts</dt><dd>{summary.facts.totalRecordedAttempts}</dd></div>
                    <div><dt>Recorded retries</dt><dd>{summary.facts.recordedRetries}</dd></div>
                    <div><dt>Observed attempt span</dt><dd>{summary.facts.observedAttemptSpanMillis === null ? 'Unavailable' : `${summary.facts.observedAttemptSpanMillis.toLocaleString()} ms`}</dd></div>
                    <div><dt>Aggregate recorded active time</dt><dd>{summary.facts.aggregateRecordedTaskDurationMillis.toLocaleString()} ms</dd></div>
                  </dl>
                  <p className="intel-note">Retry history: {summary.facts.retryHistoryComplete ? 'complete' : 'incomplete'}. Timing evidence: {summary.facts.recordedTimingComplete ? 'complete' : 'incomplete'}. Recorded attempt timing is not workflow wall-clock duration.</p>
                  {summary.facts.slowestObservedTasks.length > 0 && <><h4>Largest recorded active-time contributors</h4><ol className="intel-list">{summary.facts.slowestObservedTasks.map(task => <li key={task.taskId}><strong>{task.name}</strong> — {task.durationMillis.toLocaleString()} ms{task.shareOfAggregate !== null && ` · ${(task.shareOfAggregate * 100).toFixed(1)}% of aggregate recorded active time`}<code className="sub-id">{task.taskId}</code></li>)}</ol></>}
                  <EvidenceLimits notes={summary.facts.unavailableEvidence} />
                </div>
                <div className="intel-interpretation"><h3>Local Ollama interpretation</h3><h4>Overview</h4><p>{summary.interpretation.overview}</p>
                  {summary.interpretation.notableEvents.length > 0 && <><h4>Notable events</h4><TextList items={summary.interpretation.notableEvents} /></>}
                  {summary.interpretation.operationalNotes.length > 0 && <><h4>Operational notes</h4><TextList items={summary.interpretation.operationalNotes} /></>}
                </div>
              </div><SnapshotMetadata id={summary.summaryId} model={summary.model} generatedAt={summary.generatedAt} />
            </> : <div className="intel-empty"><p>No summary generated yet.</p>{!settled && <p className="intel-note">Wait for execution and retries to settle before generating.</p>}<button disabled={!settled || generation.isPending} onClick={requestGeneration}>{generation.isPending ? 'Generating…' : 'Generate summary'}</button></div>}
      {generation.error && <IntelligenceError error={generation.error} operation="generation" />}
    </div>
  </section>
}
