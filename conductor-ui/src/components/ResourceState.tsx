export function ResourceState({ loading, error, retry }: { loading: boolean; error: Error | null; retry: () => void }) {
  if (loading) return <div className="resource-state" role="status">Loading Conductor state…</div>
  if (error) return <div className="resource-state error" role="alert"><strong>Could not load this view</strong><p>{error.message}</p><button onClick={retry}>Try again</button></div>
  return null
}

