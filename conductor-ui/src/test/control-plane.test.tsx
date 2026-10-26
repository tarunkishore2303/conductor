import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Workflow, WorkflowTask } from '../api/types'
import { buildWorkflowGraph } from '../components/graph'
import { StatusBadge } from '../components/StatusBadge'
import { WorkflowsPage } from '../pages/WorkflowsPage'
import { RunPage } from '../pages/RunPage'

const clients: QueryClient[] = []
function TestProviders({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  clients.push(client)
  return <QueryClientProvider client={client}><MemoryRouter initialEntries={['/runs/run-1']}>{children}</MemoryRouter></QueryClientProvider>
}

function response(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

const workflow: Workflow = {
  id: 'workflow-1', name: 'Order pipeline', description: 'A real workflow',
  defaultFailurePolicy: 'FAIL_FAST', createdAt: '2026-10-24T10:00:00Z',
  tasks: [{ taskId: 'fetch', name: 'Fetch orders', type: 'HTTP_REQUEST', dependsOn: [], maxRetries: 2 }],
}

afterEach(() => {
  cleanup()
  clients.splice(0).forEach(client => client.clear())
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('workflow registry', () => {
  it('renders returned workflow names, detail links, task count, and failure policy', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(response([workflow]))
    vi.stubGlobal('fetch', fetch)
    render(<WorkflowsPage />, { wrapper: TestProviders })

    expect(await screen.findByRole('link', { name: workflow.name })).toHaveAttribute('href', '/workflows/workflow-1')
    expect(screen.getByText('FAIL_FAST')).toBeInTheDocument()
    expect(screen.getByText('1', { selector: 'td, td span' })).toBeInTheDocument()
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/workflows?limit=50')
  })

  it('shows an intentional empty state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([])))
    render(<WorkflowsPage />, { wrapper: TestProviders })
    expect(await screen.findByText(/no workflows/i)).toBeInTheDocument()
  })

  it('keeps navigation usable and offers retry without leaking raw server errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ detail: 'private database exception' }, 503)))
    render(<WorkflowsPage />, { wrapper: TestProviders })
    expect(await screen.findByRole('alert')).toHaveTextContent('Conductor API is unavailable')
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
    expect(screen.queryByText(/private database exception/)).not.toBeInTheDocument()
  })
})

describe('workflow dependency visualization', () => {
  it('lays out a diamond using logical IDs and explicit edges, preserving capability metadata', () => {
    const tasks: WorkflowTask[] = [
      { taskId: 'A', name: 'Start', dependsOn: [], type: 'NOOP', maxRetries: 0 },
      { taskId: 'B', name: 'Wait', dependsOn: ['A'], type: 'DELAY', maxRetries: 1 },
      { taskId: 'C', name: 'Call', dependsOn: ['A'], type: 'HTTP_REQUEST', maxRetries: 2 },
      { taskId: 'D', name: 'Join', dependsOn: ['B', 'C'], type: 'TRANSFORM', maxRetries: 0 },
    ]
    const graph = buildWorkflowGraph(tasks)
    expect(graph.nodes.map(node => node.id)).toEqual(['A', 'B', 'C', 'D'])
    expect(graph.edges.map(({ source, target }) => [source, target])).toEqual([
      ['A', 'B'], ['A', 'C'], ['B', 'D'], ['C', 'D'],
    ])
    expect(graph.nodes[2].data.task).toEqual(tasks[2])
    expect(graph.nodes[1].position.x).toBeGreaterThan(graph.nodes[0].position.x)
    expect(graph.nodes[3].position.x).toBeGreaterThan(graph.nodes[1].position.x)
  })
})

describe('run visibility', () => {
  it('renders a failed run with task capability, retry counters, and truthful timestamp labels', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({
      id: 'run-1', name: 'Failed notification', status: 'FAILED', failurePolicy: 'FAIL_FAST',
      createdAt: '2026-10-24T10:00:00Z', updatedAt: '2026-10-24T10:00:04Z',
      tasks: [{ id: 'task-1', name: 'Notify', type: 'HTTP_REQUEST', status: 'DEAD_LETTERED',
        retryCount: 2, maxRetries: 2, createdAt: '2026-10-24T10:00:00Z', updatedAt: '2026-10-24T10:00:04Z' }],
    })))
    render(<Routes><Route path="/runs/:jobId" element={<RunPage />} /></Routes>, { wrapper: TestProviders })
    expect(await screen.findByRole('heading', { name: 'Failed notification' })).toBeInTheDocument()
    expect(screen.getByText('FAILED')).toBeInTheDocument()
    const taskRow = screen.getByRole('row', { name: /Notify/ })
    expect(within(taskRow).getByText('HTTP_REQUEST')).toBeInTheDocument()
    expect(within(taskRow).getByText('DEAD LETTERED')).toBeInTheDocument()
    expect(within(taskRow).getByText('2', { selector: '.number' })).toBeInTheDocument()
    expect(screen.queryByText(/Auto-refreshing/)).not.toBeInTheDocument()
    expect(screen.getByText(/not active attempt duration/)).toBeInTheDocument()
  })

  it('renders dead-letter status as text rather than color alone', () => {
    render(<StatusBadge status="DEAD_LETTERED" />)
    expect(screen.getByText('DEAD LETTERED')).toBeInTheDocument()
  })

})
