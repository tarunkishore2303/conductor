import { ApiError, get, post } from './client'

export interface OptimizationEvidence {
  executionsConsidered: number; excludedExecutions: number; attemptsConsidered: number
  recordedRetries: number; executionsWithRetries: number; executionsWithFailedAttempts: number
  terminalFailures: number; recoveredExecutions: number; deadLetteredExecutions: number
  ratePercent: number | null; activeAttemptMillis: number | null; averageActiveAttemptMillis: number | null
  shareOfRecordedActiveTimePercent: number | null; comparisonActiveAttemptMillis: number | null
  durationRatio: number | null; errorTypeCounts: Record<string, number>; sharedPrerequisites: string[]
}
export interface OptimizationCandidate {
  candidateId: string
  type: 'SLOW_TASK' | 'HIGH_RETRY_RATE' | 'FAILURE_HOTSPOT' | 'DLQ_HOTSPOT' | 'DURATION_IMBALANCE' | 'PARALLEL_EXECUTION_OBSERVATION'
  severity: 'INFO' | 'WARNING'
  classification: 'EVIDENCE_BACKED' | 'STRUCTURAL_OBSERVATION'
  taskReferences: string[]
  title: string
  evidence: OptimizationEvidence
  limitation: string
}
export interface OptimizationReport {
  optimizationId: string; workflowId: string; model: string; generatedAt: string; optimizationVersion: number; contextHash: string
  facts: {
    workflowId: string; workflowVersion: number | null
    taskDefinitions: { taskId: string; type: string; dependsOn: string[] }[]
    runsConsidered: number; runIds: string[]; minimumSampleSize: number
    outcome: 'INSUFFICIENT_HISTORY' | 'NO_CANDIDATES' | 'CANDIDATES'
    historyTruncated: boolean; excludedRuns: number; historyWindowSeconds: number
    candidates: OptimizationCandidate[]; limitations: string[]; evidenceFingerprint: string
  }
  interpretation: { overview: string; recommendations: { candidateIds: string[]; recommendation: string; rationale: string }[] }
}
const resource = (id: string) => `/ai/workflows/${encodeURIComponent(id)}/optimization`
export async function getWorkflowOptimization(id: string, signal?: AbortSignal): Promise<OptimizationReport | null> {
  try { return await get<OptimizationReport>(resource(id), signal) }
  catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error }
}
export const generateWorkflowOptimization = (id: string) => post<OptimizationReport>(resource(id))
export function optimizationError(error: Error, generation = false) {
  if (error instanceof ApiError) {
    if (error.status === 503 || error.status === 504) return generation
      ? 'Local AI is currently unavailable. Stored reports remain accessible.' : 'The stored report is temporarily unavailable.'
    if (error.status === 404) return 'The workflow or report was not found.'
    if (error.status === 409) return 'Execution history is not ready. Wait for runs and retries to settle.'
    if (error.status === 422) return 'The optimization evidence or AI response could not be validated.'
  }
  return 'Unable to load the optimization report. Please try again.'
}
