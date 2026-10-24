export type TaskType = 'NOOP' | 'DELAY' | 'HTTP_REQUEST' | 'TRANSFORM'
export type RunStatus = 'CREATED' | 'RUNNING' | 'COMPLETE' | 'FAILED' | 'CANCELLING' | 'CANCELLED'
export type TaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETE' | 'FAILED' | 'DEAD_LETTERED' | 'CANCELLED'
export interface WorkflowTask {
  taskId: string
  name: string
  dependsOn: string[]
  maxRetries: number
  type: TaskType
}
export interface Workflow {
  id: string
  name: string
  description: string | null
  tasks: WorkflowTask[]
  defaultFailurePolicy: string
  createdAt: string
}
export interface RunSummary {
  id: string
  name: string
  status: RunStatus
  failurePolicy: string
  createdAt: string
  updatedAt: string
}
export interface RunTask {
  id: string
  name: string
  status: TaskStatus
  type: TaskType
  retryCount: number
  maxRetries: number
  createdAt: string
  updatedAt: string
}
export interface Run extends RunSummary { tasks: RunTask[] }
