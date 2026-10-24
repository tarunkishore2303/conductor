import { useQuery } from '@tanstack/react-query'
import { getRun, isActiveRun } from './runs'

export function useRun(id: string) {
  return useQuery({
    queryKey: ['run', id],
    queryFn: ({ signal }) => getRun(id, signal),
    // Query owns cancellation, overlapping-request prevention, and timer cleanup.
    // On error keep existing data visible and require an explicit retry.
    refetchInterval: query => query.state.status !== 'error' && isActiveRun(query.state.data?.status) ? 2000 : false,
  })
}
