import { useMemo } from 'react'
import { Background, Controls, Handle, Position, ReactFlow, type NodeProps } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { buildWorkflowGraph, type TaskNode } from './graph'
import type { WorkflowTask } from '../api/types'

function WorkflowNode({ data }: NodeProps<TaskNode>) {
  const { task } = data
  return <div className="dag-node">
    <Handle type="target" position={Position.Left} />
    <div className="node-type">{task.type}</div>
    <strong title={task.name}>{task.name}</strong>
    <code title={task.taskId}>{task.taskId}</code>
    <span className="node-retries">{task.maxRetries} max retries</span>
    <Handle type="source" position={Position.Right} />
  </div>
}
const nodeTypes = { task: WorkflowNode }

export function WorkflowDag({ tasks }: { tasks: WorkflowTask[] }) {
  const graph = useMemo(() => buildWorkflowGraph(tasks), [tasks])
  if (!tasks.length) return <div className="resource-state">No tasks in this workflow.</div>
  return <div className="dag" aria-label="Read-only workflow dependency graph">
    <ReactFlow nodes={graph.nodes} edges={graph.edges} nodeTypes={nodeTypes}
      nodesDraggable={false} nodesConnectable={false} elementsSelectable={false}
      deleteKeyCode={null} fitView fitViewOptions={{ padding: 0.2 }} minZoom={0.15} maxZoom={1.5}
      colorMode="dark" defaultEdgeOptions={{ style: { stroke: '#657b92', strokeWidth: 1.5 } }}>
      <Background color="#293544" gap={22} />
      <Controls showInteractive={false} />
    </ReactFlow>
  </div>
}
