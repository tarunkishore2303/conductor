import { useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

export const snapshotOptions = {
  staleTime: Infinity, retry: false, refetchOnMount: false,
  refetchOnReconnect: false, refetchOnWindowFocus: false,
} as const

// Stored reads and explicit generation share one lifecycle, never an execution mutation.
export function useStoredIntelligence<T>(key: string[], enabled: boolean, read: (signal: AbortSignal) => Promise<T | null>, generate: () => Promise<T>) {
  const client = useQueryClient()
  const guard = useRef(false)
  const query = useQuery({ queryKey: key, queryFn: ({ signal }) => read(signal), enabled, ...snapshotOptions })
  const generation = useMutation({
    mutationFn: generate,
    onSuccess: result => client.setQueryData(key, result),
    onSettled: () => { guard.current = false },
    retry: false,
  })
  function requestGeneration() {
    if (guard.current || query.isFetching || query.data !== null) return
    guard.current = true
    generation.mutate()
  }
  return { query, generation, requestGeneration }
}
