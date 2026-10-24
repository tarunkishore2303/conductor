import dagre from '@dagrejs/dagre'
import { Position, type Edge, type Node } from '@xyflow/react'
import type { WorkflowTask } from '../api/types'

export type TaskNode = Node<{ task: WorkflowTask }, 'task'>

export function buildWorkflowGraph(tasks: WorkflowTask[]): { nodes: TaskNode[]; edges: Edge[] } {
  const graph = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}))
  graph.setGraph({ rankdir: 'LR', nodesep: 36, ranksep: 80, marginx: 24, marginy: 24 })
  const edges = tasks.flatMap(task => task.dependsOn.map(source => ({
    id: JSON.stringify([source, task.taskId]), source, target: task.taskId, type: 'smoothstep',
  })))
  tasks.forEach(task => graph.setNode(task.taskId, { width: 240, height: 126 }))
  edges.forEach(edge => graph.setEdge(edge.source, edge.target))
  dagre.layout(graph)
  return {
    nodes: tasks.map(task => {
      const position = graph.node(task.taskId)
      return { id: task.taskId, type: 'task', data: { task }, position: { x: position.x - 120, y: position.y - 63 },
        sourcePosition: Position.Right, targetPosition: Position.Left }
    }), edges,
  }
}
