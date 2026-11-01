import type { OptimizationCandidate as Candidate } from '../../api/optimization'

export function OptimizationCandidate({ candidate }: { candidate: Candidate }) {
  const e = candidate.evidence
  const measurements: [string, number | null, string?][] = [
    ['Eligible task executions', e.executionsConsidered], ['Excluded task executions', e.excludedExecutions],
    ['Recorded attempts', e.attemptsConsidered], ['Recorded retries', e.recordedRetries],
    ['Executions with retries', e.executionsWithRetries], ['Executions with failed attempts', e.executionsWithFailedAttempts],
    ['Terminal failures', e.terminalFailures], ['Recovered executions', e.recoveredExecutions],
    ['Dead-lettered executions', e.deadLetteredExecutions], ['Observed rate', e.ratePercent, '%'],
    ['Recorded active attempt duration', e.activeAttemptMillis, ' ms'],
    ['Average active attempt duration', e.averageActiveAttemptMillis, ' ms'],
    ['Share of recorded active time', e.shareOfRecordedActiveTimePercent, '%'],
    ['Comparison active attempt duration', e.comparisonActiveAttemptMillis, ' ms'],
    ['Duration ratio', e.durationRatio],
  ]
  return <article className="intel-match opt-candidate">
    <div className="intel-match-heading"><h4>{candidate.candidateId} — {candidate.title}</h4><span className="type-label">{candidate.severity}</span></div>
    <p><code>{candidate.type}</code> · <code>{candidate.classification}</code></p>
    <p className="intel-note">Affected logical tasks: {candidate.taskReferences.map((task, index) => <span key={task}>{index > 0 && ', '}<code>{task}</code></span>)}</p>
    <dl className="intel-facts">{measurements.map(([label, value, unit]) => <div key={label}><dt>{label}</dt><dd>{value === null ? 'Unavailable' : `${value.toLocaleString()}${unit ?? ''}`}</dd></div>)}</dl>
    {Object.keys(e.errorTypeCounts).length > 0 && <p className="intel-note">Recorded error types: {Object.entries(e.errorTypeCounts).map(([type, count]) => `${type}: ${count}`).join(', ')}</p>}
    {e.sharedPrerequisites.length > 0 && <p className="intel-note">Shared prerequisites: <code>{e.sharedPrerequisites.join(', ')}</code></p>}
    <p className="intel-note">{candidate.limitation}</p>
  </article>
}
