import { afterEach, describe, expect, it, vi } from 'vitest'
import { post } from '../api/client'

afterEach(() => vi.unstubAllGlobals())
describe('typed POST transport', () => {
  it('preserves body-less generation requests without JSON headers or payload', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{}'))
    vi.stubGlobal('fetch', fetch)
    await post('/ai/runs/id/summary')
    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe('/api/v1/ai/runs/id/summary')
    expect(options.method).toBe('POST')
    expect(options.body).toBeUndefined()
    expect(options.headers).toEqual({ Accept: 'application/json' })
  })
  it('serializes a supplied JSON body and sets the correct content type', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{}'))
    vi.stubGlobal('fetch', fetch)
    const body = { question: 'How many retries?', jobId: 'job-id' }
    await post('/ai/copilot/query', body)
    expect(fetch.mock.calls[0][1]).toMatchObject({
      method: 'POST', body: JSON.stringify(body),
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    })
  })
})
