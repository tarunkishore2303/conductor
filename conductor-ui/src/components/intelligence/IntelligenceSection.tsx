import type { Run } from '../../api/types'
import { ExecutionSummaryPanel } from './ExecutionSummaryPanel'
import { FailureAnalysisPanel } from './FailureAnalysisPanel'
import { SimilarIncidentsPanel } from './SimilarIncidentsPanel'

export function IntelligenceSection({ run }: { run: Run }) {
  return <section className="intel-section" aria-labelledby="intelligence-heading">
    <header className="intel-heading"><div><p className="eyebrow">RUN INTELLIGENCE</p><h2 id="intelligence-heading">Intelligence</h2></div><p>Conductor supplies evidence. Local Ollama supplies interpretation.</p></header>
    <ExecutionSummaryPanel key={`summary-${run.id}`} run={run} />
    <FailureAnalysisPanel key={`failure-${run.id}`} run={run} />
    <SimilarIncidentsPanel key={`incidents-${run.id}`} run={run} />
  </section>
}
