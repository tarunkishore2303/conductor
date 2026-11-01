import { Link } from 'react-router-dom'
import type { CopilotEvidence as Evidence } from '../../api/copilot'

const labels: Record<string, string> = {
  jobId: 'Run', taskId: 'Task', workflowId: 'Workflow', name: 'Name', status: 'Status', type: 'Capability',
  recordedAttempts: 'Recorded attempts', recordedRetries: 'Recorded retries', maxRetries: 'Maximum retries',
  retryHistoryComplete: 'Retry history complete', recordedTimingComplete: 'Recorded timing complete',
  observedAttemptSpanMillis: 'Observed attempt span (ms)', aggregateRecordedTaskDurationMillis: 'Aggregate recorded active time (ms)',
  recordedActiveDurationMillis: 'Recorded active duration (ms)', validTimedAttempts: 'Valid timed attempts',
  attemptNumber: 'Attempt number', startedAt: 'Started', completedAt: 'Completed', retryScheduledAt: 'Retry scheduled',
  deadLetteredAt: 'Dead-lettered', errorType: 'Safe error type', similarity: 'Similarity score',
  analysisId: 'Analysis', incidentId: 'Incident', currentAnalysisId: 'Current analysis', jobStatus: 'Run status',
}
const tools = new Set(['getRun', 'getTasks', 'getFailedTasks', 'getTaskAttempts', 'getFailureAnalysis', 'getSimilarIncidents', 'getWorkflow'])
const containers = new Set(['tasks', 'attempts', 'matches', 'facts', 'slowestObservedTasks'])

// Project known metadata only. Raw JSON may include error text or incident documents.
function facts(evidence: Evidence): [string, string][] {
  if (!tools.has(evidence.tool) || evidence.factsJson.length > 4096) return []
  let parsed: unknown
  try { parsed = JSON.parse(evidence.factsJson) } catch { return [] }
  const rows: [string, string][] = []
  function visit(value: unknown, prefix: string, depth: number) {
    if (depth > 3 || !value || typeof value !== 'object' || Array.isArray(value)) return
    for (const [key, field] of Object.entries(value)) {
      if (rows.length >= 50) return
      if (labels[key] && (typeof field === 'string' || typeof field === 'boolean' || (typeof field === 'number' && Number.isFinite(field)))) {
        rows.push([`${prefix}${labels[key]}`, String(field).slice(0, 256)])
      } else if (containers.has(key)) {
        if (Array.isArray(field)) field.slice(0, 20).forEach((item, index) => visit(item, `${prefix}${key} ${index + 1} / `, depth + 1))
        else visit(field, prefix, depth + 1)
      } else if (key === 'taskStates' && field && typeof field === 'object') {
        for (const state of ['PENDING', 'RUNNING', 'COMPLETE', 'FAILED', 'DEAD_LETTERED', 'CANCELLED']) {
          const count = (field as Record<string, unknown>)[state]
          if (typeof count === 'number' && Number.isFinite(count)) rows.push([`Tasks ${state}`, String(count)])
        }
      }
    }
  }
  visit(parsed, '', 0)
  return rows.slice(0, 50)
}

export function CopilotEvidence({ evidence, cited }: { evidence: Evidence[]; cited: string[] }) {
  if (!evidence.length) return <p className="intel-note">No read-tool evidence was needed for this deterministic notice.</p>
  return <details className="cp-evidence"><summary>Evidence ({evidence.length})</summary>
    {evidence.map(item => {
      const rows = facts(item)
      return <article className="cp-evidence-item" key={item.evidenceId}>
        <h4><code>{item.evidenceId}</code> — {item.tool} {cited.includes(item.evidenceId) && <span className="type-label">Used by answer</span>}</h4>
        <p className="intel-note">{item.success ? 'Read succeeded' : 'Read failed'}{item.errorCode ? ` · ${item.errorCode}` : ''}</p>
        {rows.length > 0 && <p className="intel-note">Selected metadata only; raw text, configuration and outputs are omitted.</p>}
        {rows.length ? <dl className="intel-facts">{rows.map(([label, value], index) => <div key={index}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
          : <p className="intel-note">No displayable metadata; raw facts are intentionally omitted.</p>}
        <ul className="intel-list">{item.references.map((reference, index) => <li key={index}>{reference.kind}: {' '}
          {reference.kind === 'run' ? <Link to={`/runs/${reference.id}`}><code>{reference.id}</code></Link>
            : reference.kind === 'workflow' ? <Link to={`/workflows/${reference.id}`}><code>{reference.id}</code></Link>
              : <code>{reference.id}</code>}
        </li>)}</ul>
      </article>
    })}
  </details>
}
