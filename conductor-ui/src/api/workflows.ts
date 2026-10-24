import { get } from './client'
import type { Workflow } from './types'

export const getWorkflows = (signal?: AbortSignal) => get<Workflow[]>('/workflows?limit=50', signal)
export const getWorkflow = (id: string, signal?: AbortSignal) => get<Workflow>(`/workflows/${encodeURIComponent(id)}`, signal)
