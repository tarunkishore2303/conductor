import type { RunStatus, TaskStatus } from '../api/types'

export function StatusBadge({ status }: { status: RunStatus | TaskStatus }) {
  return <span className={`status status-${status.toLowerCase()}`}><span aria-hidden="true" />{status.replaceAll('_', ' ')}</span>
}
