import { get } from './client'
import type { Run, RunStatus, RunSummary } from './types'

export const getRuns = (signal?: AbortSignal) => get<RunSummary[]>('/jobs?limit=50', signal)
export const getRun = (id: string, signal?: AbortSignal) => get<Run>(`/jobs/${encodeURIComponent(id)}`, signal)
export const isActiveRun = (status?: RunStatus) =>
  status === 'CREATED' || status === 'RUNNING' || status === 'CANCELLING'
