const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')

export const apiUrl = (path: string) => `${baseUrl}${path}`

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}

// Never display arbitrary provider or database error internals.
async function request<T>(path: string, method: 'GET' | 'POST', signal?: AbortSignal): Promise<T> {
  const response = await fetch(apiUrl(path), { method, signal, headers: { Accept: 'application/json' } })
  if (!response.ok) {
    const message = response.status === 404 ? 'This resource was not found.'
      : response.status >= 500 ? 'Conductor API is unavailable. Try again shortly.'
        : `The API rejected this request (${response.status}).`
    throw new ApiError(response.status, message)
  }
  return response.json() as Promise<T>
}

export const get = <T>(path: string, signal?: AbortSignal) => request<T>(path, 'GET', signal)
export const post = <T>(path: string) => request<T>(path, 'POST')
