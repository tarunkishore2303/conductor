const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')

export const apiUrl = (path: string) => `${baseUrl}${path}`

// Read-only client. Never display arbitrary provider or database error internals.
export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(apiUrl(path), { signal, headers: { Accept: 'application/json' } })
  if (!response.ok) {
    const message = response.status === 404 ? 'This resource was not found.'
      : response.status >= 500 ? 'Conductor API is unavailable. Try again shortly.'
        : `The API rejected this request (${response.status}).`
    throw new Error(message)
  }
  return response.json() as Promise<T>
}
