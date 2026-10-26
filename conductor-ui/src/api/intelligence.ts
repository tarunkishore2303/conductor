import { ApiError, get, post } from './client'

export interface ExecutionSummary {
  summaryId: string
  jobId: string
  model: string
  generatedAt: string
  summaryVersion: number
  contextHash: string
  facts: {
    jobId: string; name: string; status: string; createdAt: string
    firstObservedAttemptAt: string | null; lastObservedCompletedAttemptAt: string | null
    observedAttemptSpanMillis: number | null
    taskCount: number; completedTasks: number; failedTasks: number; deadLetteredTasks: number
    cancelledTasks: number; blockedPendingTasks: number; totalRecordedAttempts: number; recordedRetries: number
    retryHistoryComplete: boolean; aggregateRecordedTaskDurationMillis: number; recordedTimingComplete: boolean
    slowestObservedTasks: { taskId: string; name: string; durationMillis: number; shareOfAggregate: number | null }[]
    failureAnalysisId: string | null; unavailableEvidence: string[]
  }
  interpretation: { overview: string; notableEvents: string[]; operationalNotes: string[] }
}
export interface FailureAnalysis {
  analysisId: string
  jobId: string
  model: string
  generatedAt: string
  analysisVersion: number
  contextHash: string
  facts: {
    jobId: string; jobStatus: string; unavailableEvidence: string[]
    tasks: {
      taskId: string; name: string; status: string; maxRetries: number; deadLetteredAt: string | null
      attempts: {
        attemptNumber: number | null; status: string; startedAt: string | null; completedAt: string | null
        errorType: string | null; retryScheduledAt: string | null
      }[]
    }[]
  }
  interpretation: { likelyCause: string; confidence: string; explanation: string; recommendedActions: string[] }
}
export interface SimilarIncidents {
  currentAnalysisId: string
  jobId: string
  embeddingModel: string
  threshold: number
  message: string
  matches: {
    incidentId: string; analysisId: string; jobId: string; taskId: string; taskName: string
    failureTimestamp: string | null; generatedAt: string; similarity: number
  }[]
}

const path = (jobId: string) => `/ai/runs/${encodeURIComponent(jobId)}`
async function stored<T>(resource: string, signal?: AbortSignal): Promise<T | null> {
  try { return await get<T>(resource, signal) }
  catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error }
}
export const getExecutionSummary = (jobId: string, signal?: AbortSignal) => stored<ExecutionSummary>(`${path(jobId)}/summary`, signal)
export const generateExecutionSummary = (jobId: string) => post<ExecutionSummary>(`${path(jobId)}/summary`)
export const getFailureAnalysis = (jobId: string, signal?: AbortSignal) => stored<FailureAnalysis>(`${path(jobId)}/failure-analysis`, signal)
export const generateFailureAnalysis = (jobId: string) => post<FailureAnalysis>(`${path(jobId)}/analyze-failure`)
export const getSimilarIncidents = (jobId: string, topK = 3) => get<SimilarIncidents>(`${path(jobId)}/similar-incidents?topK=${topK}`)

export function intelligenceError(error: Error, operation: 'generation' | 'read' | 'search') {
  if (error instanceof ApiError) {
    if (error.status === 503) return operation === 'generation'
      ? 'Local AI is currently unavailable. Existing stored intelligence remains accessible.'
      : 'Stored intelligence is temporarily unavailable. Please try again.'
    if (operation === 'search' && error.status === 404) return 'A stored failure analysis is required before searching incidents.'
    if (operation === 'search' && error.status === 409) return 'This analysis is not fully indexed. Use the existing indexing API before searching incidents.'
    if (error.status === 409) return 'This run is not ready for generation. Wait for execution and retries to settle, then try again.'
    if (error.status === 422) return 'The available evidence or AI result could not be validated.'
  }
  return 'Unable to load intelligence. Please try again.'
}
