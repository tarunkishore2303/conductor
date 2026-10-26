import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ExecutionSummary, FailureAnalysis, SimilarIncidents } from '../api/intelligence'
import type { Run } from '../api/types'
import { IntelligenceSection } from '../components/intelligence/IntelligenceSection'

const timestamp = '2026-10-26T10:00:00Z'
const run: Run = {
  id: 'run-1', name: 'Order pipeline', status: 'COMPLETE', failurePolicy: 'FAIL_FAST',
  createdAt: timestamp, updatedAt: timestamp,
  tasks: [{ id: 'task-1', name: 'Notify', status: 'COMPLETE', type: 'HTTP_REQUEST',
    retryCount: 0, maxRetries: 2, createdAt: timestamp, updatedAt: timestamp }],
}
const failedRun: Run = { ...run, status: 'FAILED', tasks: [{ ...run.tasks[0], status: 'DEAD_LETTERED', retryCount: 2 }] }
const summary: ExecutionSummary = {
  summaryId: 'summary-1', jobId: run.id, model: 'qwen2.5-coder:7b', generatedAt: timestamp,
  summaryVersion: 1, contextHash: 'summary-hash',
  facts: {
    jobId: run.id, name: run.name, status: 'COMPLETE', createdAt: timestamp,
    firstObservedAttemptAt: timestamp, lastObservedCompletedAttemptAt: timestamp, observedAttemptSpanMillis: 1200,
    taskCount: 1, completedTasks: 1, failedTasks: 0, deadLetteredTasks: 0, cancelledTasks: 0,
    blockedPendingTasks: 0, totalRecordedAttempts: 1, recordedRetries: 0, retryHistoryComplete: true,
    aggregateRecordedTaskDurationMillis: 1200, recordedTimingComplete: true,
    slowestObservedTasks: [{ taskId: 'task-1', name: 'Notify', durationMillis: 1200, shareOfAggregate: 1 }],
    failureAnalysisId: null, unavailableEvidence: ['Recorded attempts do not measure wall-clock runtime.'],
  },
  interpretation: { overview: 'The notification completed normally.', notableEvents: ['All tasks completed.'], operationalNotes: ['No intervention suggested.'] },
}
const analysis: FailureAnalysis = {
  analysisId: 'analysis-1', jobId: run.id, model: 'qwen2.5-coder:7b', generatedAt: timestamp,
  analysisVersion: 1, contextHash: 'analysis-hash',
  facts: { jobId: run.id, jobStatus: 'FAILED', unavailableEvidence: ['External dependency health is unavailable.'],
    tasks: [{ taskId: 'task-1', name: 'Notify', status: 'DEAD_LETTERED', maxRetries: 2, deadLetteredAt: timestamp,
      attempts: [{ attemptNumber: 2, status: 'FAILED', startedAt: timestamp, completedAt: timestamp,
        errorType: 'HTTP_STATUS_500', retryScheduledAt: null }] }] },
  interpretation: { likelyCause: 'The dependency repeatedly failed.', confidence: 'MEDIUM',
    explanation: 'Persisted attempts report an HTTP failure.', recommendedActions: ['Check dependency health before retrying.'] },
}
const incident = (id: string, jobId: string, similarity = 0.9625) => ({
  incidentId: id, analysisId: 'historical-analysis-' + id, jobId, taskId: 'historical-task-' + id,
  taskName: 'Notify ' + id, failureTimestamp: timestamp, generatedAt: timestamp, similarity,
})
const retrieval = (matches: SimilarIncidents['matches'] = []): SimilarIncidents => ({
  currentAnalysisId: analysis.analysisId, jobId: run.id, embeddingModel: 'nomic-embed-text:v1.5',
  threshold: 0.93, message: matches.length ? 'Retrieved historical incident evidence' : 'No historical incidents meet the configured similarity threshold', matches,
})

const clients: QueryClient[] = []
function mount(value: Run = run) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  clients.push(client)
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>
  }
  return { ...render(<IntelligenceSection run={value} />, { wrapper: Wrapper }), client }
}
const reply = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json' },
})
function mockApi(handler: (path: string, method: string) => Response | Promise<Response>) {
  const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation((url, init) => Promise.resolve(handler(String(url), init?.method ?? 'GET')))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
const missing = () => reply({ detail: 'No stored result' }, 404)
afterEach(() => {
  cleanup()
  clients.splice(0).forEach(client => client.clear())
  vi.unstubAllGlobals()
})

describe('execution summary', () => {
  it('reads the stored snapshot without POST and separates facts from model prose', async () => {
    const fetch = mockApi(path => path.endsWith('/summary') ? reply(summary) : missing())
    mount()
    expect(await screen.findByText(summary.interpretation.overview)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Facts.*Conductor/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Local Ollama interpretation/i })).toBeInTheDocument()
    expect(screen.getByText('Recorded attempts do not measure wall-clock runtime.')).toBeInTheDocument()
    expect(fetch.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(true)
    expect(screen.queryByRole('button', { name: /Generate summary/i })).not.toBeInTheDocument()
  })

  it('generates only after an explicit action and reuses the returned cache entry', async () => {
    const fetch = mockApi((path, method) => path.endsWith('/summary') && method === 'POST' ? reply(summary) : missing())
    const { client } = mount()
    const generate = await screen.findByRole('button', { name: /Generate summary/i })
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    fireEvent.click(generate)
    expect(await screen.findByText(summary.interpretation.overview)).toBeInTheDocument()
    expect(client.getQueryData(['summary', run.id])).toEqual(summary)
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('disables duplicate summary generation while the request is pending', async () => {
    let finish!: (response: Response) => void
    const fetch = mockApi((path, method) => path.endsWith('/summary') && method === 'POST'
      ? new Promise<Response>(resolve => { finish = resolve }) : missing())
    mount()
    fireEvent.click(await screen.findByRole('button', { name: /Generate summary/i }))
    const pending = await screen.findByRole('button', { name: /Generating/i })
    expect(pending).toBeDisabled()
    fireEvent.click(pending)
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    await act(async () => { finish(reply(summary)) })
    expect(await screen.findByText(summary.interpretation.overview)).toBeInTheDocument()
  })

  it('shows an unavailable generation state without provider internals', async () => {
    mockApi((_path, method) => method === 'POST' ? reply({ detail: 'private provider token/stack' }, 503) : missing())
    mount()
    fireEvent.click(await screen.findByRole('button', { name: /Generate summary/i }))
    expect(await screen.findByText(/Local AI is currently unavailable/)).toBeInTheDocument()
    expect(screen.queryByText(/private provider token/)).not.toBeInTheDocument()
  })

  it.each(['RUNNING', 'CANCELLED'] as const)('does not offer generation or query intelligence for a %s run', async status => {
    const fetch = mockApi(missing)
    mount({ ...run, status })
    expect(screen.queryByRole('button', { name: /Generate summary|Analyze failure|Find similar incidents/i })).not.toBeInTheDocument()
    await act(async () => { await Promise.resolve() })
    expect(fetch).not.toHaveBeenCalled()
  })
})

describe('failure analysis', () => {
  it('does not offer failure generation for a successful run', async () => {
    mockApi(missing)
    mount()
    expect(screen.queryByRole('button', { name: /Analyze failure/i })).not.toBeInTheDocument()
    expect(screen.getByText(/only available for failed runs/i)).toBeInTheDocument()
    await screen.findByRole('button', { name: /Generate summary/i })
  })

  it('renders stored evidence and neutral confidence without raw error messages', async () => {
    const privateAnalysis = { ...analysis, facts: { ...analysis.facts, tasks: [{ ...analysis.facts.tasks[0],
      attempts: [{ ...analysis.facts.tasks[0].attempts[0], errorMessage: 'hidden raw provider error' }] }] } }
    const fetch = mockApi(path => path.endsWith('/failure-analysis') ? reply(privateAnalysis) : missing())
    mount(failedRun)
    expect(await screen.findByText(analysis.interpretation.likelyCause)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Evidence.*Conductor/i })).toBeInTheDocument()
    expect(screen.getByText(/Model confidence/i)).toBeInTheDocument()
    expect(screen.getByText('MEDIUM')).toBeInTheDocument()
    expect(screen.getByText('HTTP_STATUS_500')).toBeInTheDocument()
    expect(screen.queryByText(/hidden raw provider error|% chance/i)).not.toBeInTheDocument()
    expect(fetch.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(true)
  })

  it('offers explicit failure analysis after a stored-result miss and renders POST result', async () => {
    const fetch = mockApi((path, method) => path.endsWith('/analyze-failure') && method === 'POST' ? reply(analysis) : missing())
    const { client } = mount(failedRun)
    fireEvent.click(await screen.findByRole('button', { name: /Analyze failure/i }))
    expect(await screen.findByText(analysis.interpretation.likelyCause)).toBeInTheDocument()
    expect(client.getQueryData(['failure-analysis', run.id])).toEqual(analysis)
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })
})

describe('historical incidents', () => {
  it('searches explicitly and renders a deterministic no-match state', async () => {
    const fetch = mockApi(path => path.includes('/similar-incidents?') ? reply(retrieval()) : missing())
    mount(failedRun)
    expect(fetch.mock.calls.some(([path]) => String(path).includes('/similar-incidents'))).toBe(false)
    fireEvent.click(await screen.findByRole('button', { name: /Find similar incidents/i }))
    expect(await screen.findByText(/No sufficiently similar historical incidents/i)).toBeInTheDocument()
    expect(fetch.mock.calls.find(([path]) => String(path).includes('/similar-incidents'))?.[0]).toBe('/api/v1/ai/runs/run-1/similar-incidents?topK=3')
  })

  it('renders inspectable retrieval scores and historical links, excluding the current run', async () => {
    mockApi(path => path.includes('/similar-incidents?') ? reply({ ...retrieval([
      incident('one', 'previous-run-1'), incident('two', 'previous-run-2', 0.95), incident('self', run.id),
    ]), currentIncident: 'private current document', matches: [
      { ...incident('one', 'previous-run-1'), documentText: 'private historical document' },
      incident('two', 'previous-run-2', 0.95), incident('self', run.id),
    ] }) : missing())
    mount(failedRun)
    fireEvent.click(await screen.findByRole('button', { name: /Find similar incidents/i }))
    const link = await screen.findByRole('link', { name: /previous-run-1/ })
    expect(link).toHaveAttribute('href', '/runs/previous-run-1')
    expect(screen.getByRole('link', { name: /previous-run-2/ })).toHaveAttribute('href', '/runs/previous-run-2')
    expect(screen.getByText(/Similarity 0.963/)).toBeInTheDocument()
    expect(screen.getByText(/not a probability/i)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: run.id })).not.toBeInTheDocument()
    expect(screen.queryByText(/private historical document|private current document|96.25%/)).not.toBeInTheDocument()
  })

  it('explains missing indexing safely without adding a model or indexing action', async () => {
    mockApi(path => path.includes('/similar-incidents?') ? reply({ detail: 'private vector SQL failure' }, 409) : missing())
    mount(failedRun)
    fireEvent.click(await screen.findByRole('button', { name: /Find similar incidents/i }))
    expect(await screen.findByText(/not fully indexed/i)).toBeInTheDocument()
    expect(screen.queryByText(/private vector SQL failure/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Index|Synthesize/i })).not.toBeInTheDocument()
  })
})
