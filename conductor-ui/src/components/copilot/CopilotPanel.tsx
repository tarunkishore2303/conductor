import { useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { copilotError, queryCopilot } from '../../api/copilot'
import { formatTime } from '../../api/time'
import { CopilotEvidence } from './CopilotEvidence'

const suggestions = ['Why did this run fail?', 'Which tasks failed?', 'How many retries occurred?', 'Have we seen this before?', 'What was the slowest task?']

export function CopilotPanel({ jobId }: { jobId: string }) {
  const [question, setQuestion] = useState('')
  const [validation, setValidation] = useState('')
  const guard = useRef(false)
  const query = useMutation({ mutationFn: (value: string) => queryCopilot(jobId, value), retry: false,
    onSettled: () => { guard.current = false } })
  function ask() {
    if (guard.current) return
    const value = question.trim()
    if (!value || value.length > 2000) { setValidation('Enter a question containing 1–2000 characters.'); return }
    setValidation('')
    guard.current = true
    query.reset()
    query.mutate(value)
  }
  const result = query.data
  return <section className="panel cp-panel" aria-labelledby="copilot-heading">
    <div className="panel-heading"><h2 id="copilot-heading">Ask Conductor</h2><span className="type-label">READ ONLY</span></div>
    <div className="intel-body">
      <p className="intel-note">Single-turn operational questions, grounded in allowlisted read tools. Copilot cannot control execution.</p>
      <form onSubmit={event => { event.preventDefault(); ask() }}>
        <label className="cp-label" htmlFor="copilot-question">Question about this run</label>
        <textarea id="copilot-question" rows={3} maxLength={2000} value={question} disabled={query.isPending}
          onChange={event => { setQuestion(event.target.value); setValidation('') }}
          onKeyDown={event => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); ask() } }}
          aria-describedby="copilot-help" />
        <div className="cp-submit"><span id="copilot-help" className="intel-note">{question.trim().length}/2000 · Ctrl/Cmd+Enter to ask</span><button disabled={query.isPending} type="submit">{query.isPending ? 'Asking…' : 'Ask'}</button></div>
      </form>
      <div className="cp-suggestions" aria-label="Suggested read-only questions">{suggestions.map(text => <button type="button" key={text} disabled={query.isPending} onClick={() => { setQuestion(text); setValidation('') }}>{text}</button>)}</div>
      {validation && <p className="intel-error" role="alert">{validation}</p>}
      {query.error && <p className="intel-error" role="alert">{copilotError(query.error)}</p>}
      {result && <div className="cp-answer">
        <h3>{result.answerSource === 'DETERMINISTIC_NOTICE' ? 'Deterministic Conductor notice' : 'Local Ollama interpretation'}</h3>
        <p>{result.answer}</p>
        <p className="intel-note">Read only: {result.readOnly ? 'true' : 'false'} · {formatTime(result.generatedAt)}{result.model ? ` · ${result.model}` : ''}</p>
        <h4>Tools used</h4><p>{result.toolsUsed.length ? result.toolsUsed.join(', ') : 'None'}</p>
        <h4>Evidence cited by answer</h4><p>{result.evidenceIds.length ? result.evidenceIds.join(', ') : 'None'}</p>
        <CopilotEvidence evidence={result.evidence} cited={result.evidenceIds} />
      </div>}
    </div>
  </section>
}
