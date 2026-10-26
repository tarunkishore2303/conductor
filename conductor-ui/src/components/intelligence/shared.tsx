import { intelligenceError } from '../../api/intelligence'
import { formatTime } from '../../api/time'

export function IntelligenceError({ error, operation, retry }: { error: Error; operation: 'read' | 'generation' | 'search'; retry?: () => void }) {
  return <div className="intel-error" role="alert"><p>{intelligenceError(error, operation)}</p>{retry && <button onClick={retry}>Try again</button>}</div>
}

export function SnapshotMetadata({ id, model, generatedAt }: { id: string; model: string; generatedAt: string }) {
  return <div className="intel-metadata"><span>Model <code>{model}</code></span><span>Generated {formatTime(generatedAt)}</span><span>Snapshot <code>{id}</code></span></div>
}

export function EvidenceLimits({ notes }: { notes: string[] }) {
  if (!notes.length) return null
  return <details className="intel-limits"><summary>Evidence limitations</summary><ul>{notes.map((note, index) => <li key={index}>{note}</li>)}</ul></details>
}

export function TextList({ items }: { items: string[] }) {
  return <ul className="intel-list">{items.map((item, index) => <li key={index}>{item}</li>)}</ul>
}
