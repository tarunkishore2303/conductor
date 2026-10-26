import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useRun } from '../api/queries'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  listeners = new Map<string, Set<() => void>>()
  close = vi.fn()
  readonly url: string
  constructor(url: string) { this.url = url; FakeEventSource.instances.push(this) }
  addEventListener(type: string, listener: () => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type)!.add(listener)
  }
  removeEventListener(type: string, listener: () => void) { this.listeners.get(type)?.delete(listener) }
  emit(type: string) { this.listeners.get(type)?.forEach(listener => listener()) }
}

const clients: QueryClient[] = []
function Wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  clients.push(client)
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}
function Probe({ id = 'run-1' }: { id?: string }) {
  const query = useRun(id)
  return <div role="status">{query.data?.status ?? 'Loading'} / {query.streamStatus}</div>
}
const reply = (status = 'RUNNING') => new Response(JSON.stringify({ id: 'run-1', status }), {
  headers: { 'Content-Type': 'application/json' },
})
const advance = async (milliseconds: number) => { await act(async () => { await vi.advanceTimersByTimeAsync(milliseconds) }) }

beforeEach(() => {
  vi.useFakeTimers()
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
})
afterEach(() => {
  cleanup()
  clients.splice(0).forEach(client => client.clear())
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('run live notifications', () => {
  it('opens only after the active REST snapshot and does not poll a healthy stream', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(reply()))
    vi.stubGlobal('fetch', fetch)
    render(<Probe />, { wrapper: Wrapper })
    expect(FakeEventSource.instances).toHaveLength(0)
    await advance(1)
    expect(FakeEventSource.instances[0].url).toBe('/api/v1/jobs/run-1/events')
    act(() => FakeEventSource.instances[0].onopen?.())
    await advance(500)
    expect(screen.getByRole('status')).toHaveTextContent('live')
    expect(fetch).toHaveBeenCalledTimes(2) // open recovers the REST/subscription gap
    await advance(10000)
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it.each(['COMPLETE', 'FAILED', 'CANCELLED'])('does not subscribe to a terminal %s snapshot', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(status)))
    render(<Probe />, { wrapper: Wrapper })
    await advance(1)
    expect(FakeEventSource.instances).toHaveLength(0)
  })

  it('coalesces known events, refetches REST, and closes when REST becomes terminal', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(reply()).mockResolvedValueOnce(reply()).mockResolvedValue(reply('COMPLETE'))
    vi.stubGlobal('fetch', fetch)
    render(<Probe />, { wrapper: Wrapper })
    await advance(1)
    const stream = FakeEventSource.instances[0]
    act(() => stream.onopen?.())
    await advance(500)
    act(() => { stream.emit('TASK_COMPLETED'); stream.emit('JOB_COMPLETED'); stream.emit('TASK_COMPLETED') })
    await advance(500)
    expect(screen.getByRole('status')).toHaveTextContent('COMPLETE')
    expect(stream.close).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledTimes(3)
    await advance(10000)
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('ignores unknown events and heartbeat comments', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(reply()))
    vi.stubGlobal('fetch', fetch)
    render(<Probe />, { wrapper: Wrapper })
    await advance(1)
    const stream = FakeEventSource.instances[0]
    act(() => { stream.emit('heartbeat'); stream.emit('EXECUTE_SQL'); stream.emit('message') })
    await advance(1000)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('uses five-second fallback on error and disables polling on native reconnect', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(reply()))
    vi.stubGlobal('fetch', fetch)
    render(<Probe />, { wrapper: Wrapper })
    await advance(1)
    const stream = FakeEventSource.instances[0]
    act(() => stream.onerror?.())
    await advance(5000)
    expect(screen.getByRole('status')).toHaveTextContent('fallback')
    expect(fetch).toHaveBeenCalledTimes(2)
    act(() => stream.onopen?.())
    await advance(500)
    expect(screen.getByRole('status')).toHaveTextContent('live')
    expect(fetch).toHaveBeenCalledTimes(3)
    await advance(10000)
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('cleans up its connection and queued invalidation on navigation', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(reply()))
    vi.stubGlobal('fetch', fetch)
    const view = render(<Probe />, { wrapper: Wrapper })
    await advance(1)
    const stream = FakeEventSource.instances[0]
    act(() => stream.emit('TASK_COMPLETED'))
    view.unmount()
    await advance(10000)
    expect(stream.close).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('falls back when EventSource is unavailable', async () => {
    vi.stubGlobal('EventSource', undefined)
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(reply()))
    vi.stubGlobal('fetch', fetch)
    render(<Probe />, { wrapper: Wrapper })
    await advance(1)
    await advance(5000)
    expect(screen.getByRole('status')).toHaveTextContent('fallback')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
