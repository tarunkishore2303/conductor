import { ApiError, post } from './client'

export interface CopilotEvidence {
  evidenceId: string
  tool: string
  success: boolean
  errorCode: string | null
  factsJson: string
  references: { kind: 'run' | 'task' | 'analysis' | 'incident' | 'workflow'; id: string; url: string }[]
}
export interface CopilotResponse {
  answer: string
  evidence: CopilotEvidence[]
  toolsUsed: string[]
  evidenceIds: string[]
  model: string | null
  generatedAt: string
  readOnly: boolean
  answerSource: 'OLLAMA_INTERPRETATION' | 'DETERMINISTIC_NOTICE'
}
export function queryCopilot(jobId: string, question: string) {
  const trimmed = question.trim()
  if (!trimmed || trimmed.length > 2000) throw new Error('Question must contain 1–2000 characters.')
  // Exactly one scope; this UI intentionally exposes run scope only.
  return post<CopilotResponse>('/ai/copilot/query', { question: trimmed, jobId })
}
export function copilotError(error: Error) {
  if (error instanceof ApiError) {
    if (error.status === 503 || error.status === 504) return 'Local AI is currently unavailable. Please try again later.'
    if (error.status === 400) return 'The question or scope was rejected.'
    if (error.status === 422) return 'The AI response could not be validated.'
    if (error.status === 404) return 'The requested evidence scope was not found.'
  }
  return 'Unable to answer this question. Please try again.'
}
