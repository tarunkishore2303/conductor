import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OptimizationReport } from '../api/optimization'
import { WorkflowOptimizationPanel } from '../components/optimization/WorkflowOptimizationPanel'

const report: OptimizationReport = {
  optimizationId: 'report-1', workflowId: 'workflow-1', model: 'qwen2.5-coder:7b', generatedAt: '2026-11-01T12:00:00Z', optimizationVersion: 1, contextHash: 'hash-1',
  facts: { workflowId: 'workflow-1', workflowVersion: 0, taskDefinitions: [], runsConsidered: 5, runIds: ['run-1'], minimumSampleSize: 5,
    outcome: 'CANDIDATES', historyTruncated: false, excludedRuns: 1, historyWindowSeconds: 2592000, evidenceFingerprint: 'evidence-hash', limitations: ['Structure does not prove business independence.'],
    candidates: [{ candidateId: 'O1', type: 'HIGH_RETRY_RATE', severity: 'WARNING', classification: 'EVIDENCE_BACKED', taskReferences: ['notify'], title: 'Recurring retries', limitation: 'Correlation does not establish cause.',
      evidence: { executionsConsidered: 5, excludedExecutions: 0, attemptsConsidered: 8, recordedRetries: 3, executionsWithRetries: 2, executionsWithFailedAttempts: 2,
        terminalFailures: 1, recoveredExecutions: 1, deadLetteredExecutions: 1, ratePercent: 40, activeAttemptMillis: null, averageActiveAttemptMillis: null,
        shareOfRecordedActiveTimePercent: null, comparisonActiveAttemptMillis: null, durationRatio: null, errorTypeCounts: { HTTP_STATUS_500: 3 }, sharedPrerequisites: [] } }] },
  interpretation: { overview: 'Investigate the recurring notification retries.', recommendations: [{ candidateIds: ['O1'], recommendation: 'Check dependency health.', rationale: 'Two of five executions retried.' }] },
}
const clients: QueryClient[] = []
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  clients.push(client)
  render(<QueryClientProvider client={client}><MemoryRouter><WorkflowOptimizationPanel workflowId="workflow-1" /></MemoryRouter></QueryClientProvider>)
  return client
}
const reply = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
function api(handler: (method: string) => Response | Promise<Response>) {
  const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation((_url, init) => Promise.resolve(handler(init?.method ?? 'GET')))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); vi.unstubAllGlobals() })

describe('workflow optimization', () => {
  it('reads stored evidence without POST or AI health gating and labels advisory-only output', async () => {
    const fetch = api(() => reply(report)); mount()
    expect(await screen.findByText(report.interpretation.overview)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Deterministic analyzer evidence/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Local Ollama interpretation' })).toBeInTheDocument()
    expect(screen.getByText('ADVISORY ONLY')).toBeInTheDocument()
    expect(screen.getByText('HIGH_RETRY_RATE')).toBeInTheDocument()
    expect(screen.getByText('40%')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'run-1' })).toHaveAttribute('href', '/runs/run-1')
    expect(screen.queryByRole('button', { name: /Apply|Accept|Rewrite/i })).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/ai/workflows/workflow-1/optimization')
    expect(fetch.mock.calls[0][1]?.method).toBe('GET')
  })
  it('shows unavailable null measurements rather than inventing zeros', async () => {
    api(() => reply(report)); mount()
    await screen.findByText(report.interpretation.overview)
    expect(screen.getAllByText('Unavailable')).toHaveLength(5)
    expect(screen.getByText(/Rates are observed execution frequencies, not probabilities/)).toBeInTheDocument()
  })
  it('generates only explicitly after a404 and populates only the report cache', async () => {
    const fetch = api(method => method === 'POST' ? reply(report) : reply({}, 404))
    const client = mount()
    const button = await screen.findByRole('button', { name: 'Generate optimization report' })
    expect(fetch).toHaveBeenCalledTimes(1)
    fireEvent.click(button)
    expect(await screen.findByText(report.interpretation.overview)).toBeInTheDocument()
    expect(client.getQueryData(['optimization', 'workflow-1'])).toEqual(report)
    expect(client.getQueryData(['run', 'workflow-1'])).toBeUndefined()
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })
  it('disables duplicate generation while a POST is pending', async () => {
    let resolve!: (value: Response) => void
    const fetch = api(method => method === 'POST' ? new Promise<Response>(done => { resolve = done }) : reply({}, 404))
    mount(); const button = await screen.findByRole('button', { name: 'Generate optimization report' })
    fireEvent.click(button); fireEvent.click(button)
    expect(await screen.findByRole('button', { name: 'Generating…' })).toBeDisabled()
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    await act(async () => resolve(reply(report)))
    expect(await screen.findByText(report.interpretation.overview)).toBeInTheDocument()
  })
  it.each(['NO_CANDIDATES', 'INSUFFICIENT_HISTORY'] as const)('renders %s as a deterministic result, not an AI error', async outcome => {
    api(() => reply({ ...report, model: 'deterministic', facts: { ...report.facts, outcome, candidates: [] }, interpretation: { overview: 'No model call was needed.', recommendations: [] } }))
    mount()
    expect(await screen.findByRole('heading', { name: 'Deterministic Conductor notice' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Local Ollama interpretation' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText(outcome === 'NO_CANDIDATES' ? /No optimization candidates were identified/ : /Insufficient settled linked execution history/)).toBeInTheDocument()
  })
  it('keeps provider internals hidden when explicit generation returns503', async () => {
    api(method => method === 'POST' ? reply({ detail: 'private model token stack trace' }, 503) : reply({}, 404))
    mount(); fireEvent.click(await screen.findByRole('button', { name: 'Generate optimization report' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Local AI is currently unavailable. Stored reports remain accessible.')
    expect(screen.queryByText(/private model token/)).not.toBeInTheDocument()
  })
})
