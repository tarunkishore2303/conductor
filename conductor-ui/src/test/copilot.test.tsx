import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CopilotResponse } from '../api/copilot'
import { CopilotPanel } from '../components/copilot/CopilotPanel'

const answer: CopilotResponse = {
  answer: 'Two retries were recorded.', answerSource: 'OLLAMA_INTERPRETATION', readOnly: true,
  model: 'qwen2.5-coder:7b', generatedAt: '2026-11-01T10:00:00Z', toolsUsed: ['getRun'], evidenceIds: ['E1'],
  evidence: [{ evidenceId: 'E1', tool: 'getRun', success: true, errorCode: null,
    factsJson: JSON.stringify({ status: 'FAILED', recordedRetries: 2, output: 'private-output', errorMessage: 'private-error', sql: 'private-sql' }),
    references: [{ kind: 'run', id: 'run-1', url: 'https://untrusted.invalid' },
      { kind: 'workflow', id: 'workflow-1', url: 'javascript:alert(1)' }, { kind: 'analysis', id: 'analysis-1', url: '/not-a-ui-route' }] }],
}
const clients: QueryClient[] = []
function mount() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  clients.push(client)
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>
  }
  return render(<CopilotPanel jobId="run-1" />, { wrapper: Wrapper })
}
const reply = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status,
  headers: { 'Content-Type': 'application/json' } })
function ask(question = 'How many retries?') {
  fireEvent.change(screen.getByLabelText('Question about this run'), { target: { value: question } })
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
}
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); vi.unstubAllGlobals() })

describe('run Copilot', () => {
  it('does not request on mount or suggestions, validates empty/oversized questions', () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    mount()
    ask('   ')
    expect(screen.getByRole('alert')).toHaveTextContent('1–2000')
    ask('x'.repeat(2001))
    expect(fetch).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Which tasks failed?' }))
    expect(screen.getByLabelText('Question about this run')).toHaveValue('Which tasks failed?')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('posts a trimmed question with exactly the current run scope', async () => {
    const fetch = vi.fn().mockResolvedValue(reply(answer))
    vi.stubGlobal('fetch', fetch)
    mount(); ask('  How many retries?  ')
    expect(await screen.findByText(answer.answer)).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/v1/ai/copilot/query')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ Accept: 'application/json', 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body)).toEqual({ question: 'How many retries?', jobId: 'run-1' })
  })

  it('shows the model source, tools and cited evidence with safe UI references', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(answer)))
    mount(); ask()
    expect(await screen.findByRole('heading', { name: 'Local Ollama interpretation' })).toBeInTheDocument()
    expect(screen.getByText('Read only: true', { exact: false })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Tools used' })).toBeInTheDocument()
    expect(screen.getByText('Used by answer')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Evidence (1)'))
    expect(screen.getByRole('link', { name: 'run-1' })).toHaveAttribute('href', '/runs/run-1')
    expect(screen.getByRole('link', { name: 'workflow-1' })).toHaveAttribute('href', '/workflows/workflow-1')
    expect(screen.getByText('analysis-1')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'analysis-1' })).not.toBeInTheDocument()
    expect(screen.getByText('Recorded retries')).toBeInTheDocument()
    expect(screen.queryByText(/private-output|private-error|private-sql/)).not.toBeInTheDocument()
  })

  it('prevents duplicate submissions while pending', async () => {
    let finish!: (response: Response) => void
    const fetch = vi.fn().mockReturnValue(new Promise<Response>(resolve => { finish = resolve }))
    vi.stubGlobal('fetch', fetch)
    mount(); ask()
    const pending = await screen.findByRole('button', { name: 'Asking…' })
    expect(pending).toBeDisabled()
    fireEvent.click(pending)
    expect(fetch).toHaveBeenCalledTimes(1)
    await act(async () => finish(reply(answer)))
    expect(await screen.findByText(answer.answer)).toBeInTheDocument()
  })

  it('renders a read-only refusal as a deterministic notice, not an error', async () => {
    const notice: CopilotResponse = { ...answer, answer: 'Conductor Copilot is read-only and cannot retry tasks.',
      answerSource: 'DETERMINISTIC_NOTICE', model: null, evidence: [], toolsUsed: [], evidenceIds: [] }
    const fetch = vi.fn().mockResolvedValue(reply(notice))
    vi.stubGlobal('fetch', fetch)
    mount(); ask('Retry the failed task')
    expect(await screen.findByRole('heading', { name: 'Deterministic Conductor notice' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Local Ollama interpretation' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['/api/v1/ai/copilot/query'])
  })

  it.each([
    [503, 'Local AI is currently unavailable'], [400, 'The question or scope was rejected'],
    [422, 'The AI response could not be validated'],
  ])('maps %s to safe operational text', async (status, message) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply({ detail: 'private-provider-token-stack' }, status)))
    mount(); ask()
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByText(/private-provider/)).not.toBeInTheDocument()
  })

  it('keeps invalid JSON as metadata-only evidence without rendering it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply({ ...answer,
      evidence: [{ ...answer.evidence[0], success: false, errorCode: 'READ_UNAVAILABLE', factsJson: '<script>private</script>' }] })))
    mount(); ask()
    expect(await screen.findByText('No displayable metadata; raw facts are intentionally omitted.')).toBeInTheDocument()
    expect(screen.getByText(/READ_UNAVAILABLE/)).toBeInTheDocument()
    expect(screen.queryByText(/<script>/)).not.toBeInTheDocument()
  })
})
