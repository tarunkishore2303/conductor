import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { runEventsUrl } from './runs'

export type RunStreamStatus = 'connecting' | 'live' | 'fallback' | 'closed'

// An allowlist of invalidation hints, never commands or authoritative task state.
const eventTypes = [
  'JOB_STARTED', 'JOB_COMPLETED', 'JOB_FAILED', 'JOB_CANCELLED', 'JOB_CANCELLING',
  'TASK_STARTED', 'TASK_COMPLETED', 'TASK_RETRY_SCHEDULED', 'TASK_DEAD_LETTERED',
  'TASK_CANCELLED', 'TASK_FAILED',
] as const

export function useRunEvents(jobId: string, active: boolean, onStatusChange: (status: RunStreamStatus) => void) {
  const client = useQueryClient()

  useEffect(() => {
    if (!active) {
      onStatusChange('closed')
      return
    }
    onStatusChange('connecting')
    if (typeof EventSource === 'undefined') {
      onStatusChange('fallback')
      return
    }

    let disposed = false
    let dirty = false
    let refreshing = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let source: EventSource
    const queryKey = ['run', jobId]

    // Coalesce bursts; preserve a second refresh if a hint arrives during a fetch.
    // Snapshot contents stay entirely under TanStack Query's ownership.
    const scheduleRefresh = () => {
      dirty = true
      if (timer === undefined && !disposed) timer = setTimeout(() => { void refresh() }, 150)
    }
    const refresh = async () => {
      timer = undefined
      if (disposed || refreshing) return
      refreshing = true
      dirty = false
      try {
        await client.invalidateQueries({ queryKey, exact: true }, { cancelRefetch: false })
      } finally {
        refreshing = false
        if (dirty && !disposed) scheduleRefresh()
      }
    }

    try {
      source = new EventSource(runEventsUrl(jobId))
    } catch {
      onStatusChange('fallback')
      return
    }
    source.onopen = () => {
      if (disposed) return
      onStatusChange('live')
      // There is no replay guarantee. Recover missed transitions on every open.
      scheduleRefresh()
    }
    source.onerror = () => {
      if (!disposed) onStatusChange('fallback')
      // EventSource itself owns reconnection. A successful open stops fallback.
    }
    eventTypes.forEach(type => source.addEventListener(type, scheduleRefresh))

    return () => {
      disposed = true
      if (timer !== undefined) clearTimeout(timer)
      eventTypes.forEach(type => source.removeEventListener(type, scheduleRefresh))
      source.onopen = null
      source.onerror = null
      source.close()
    }
  }, [jobId, active, client, onStatusChange])
}
