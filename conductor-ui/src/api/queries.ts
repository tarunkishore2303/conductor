import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getRun, isActiveRun } from './runs'
import { useRunEvents, type RunStreamStatus } from './useRunEvents'

export function useRun(id: string) {
  const [streamStatus, setStreamStatus] = useState<RunStreamStatus>('closed')
  const query = useQuery({
    queryKey: ['run', id],
    queryFn: ({ signal }) => getRun(id, signal),
    // Healthy streams never poll. Only active runs use the degraded 5s fallback.
    refetchInterval: query => streamStatus === 'fallback' && isActiveRun(query.state.data?.status) ? 5000 : false,
  })
  useRunEvents(id, isActiveRun(query.data?.status), setStreamStatus)
  return { ...query, streamStatus }
}
