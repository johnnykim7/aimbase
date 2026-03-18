import type { Node, Edge } from "@xyflow/react";
import dagre from "dagre";
import type { Workflow } from "../../../types/workflow";

const NODE_WIDTH = 200;
const NODE_HEIGHT = 60;

/**
 * WorkflowEntity → React Flow 노드/엣지 변환
 */
export function workflowToFlow(workflow: Workflow): { nodes: Node[]; edges: Edge[] } {
  const steps = workflow.steps ?? [];
  if (steps.length === 0) return { nodes: [], edges: [] };

  const nodes: Node[] = steps.map((step, i) => ({
    id: step.id,
    type: "workflowNode",
    position: { x: 0, y: i * 120 },
    data: {
      label: step.name,
      type: step.type,
      config: step.config ?? {},
    },
  }));

  const edges: Edge[] = [];
  steps.forEach((step) => {
    const deps = step.dependsOn ?? [];
    deps.forEach((depId) => {
      edges.push({
        id: `${depId}->${step.id}`,
        source: depId,
        target: step.id,
        animated: false,
      });
    });

    if (deps.length === 0 && step.nextSteps) {
      step.nextSteps.forEach((nextId) => {
        const edgeId = `${step.id}->${nextId}`;
        if (!edges.find((e) => e.id === edgeId)) {
          edges.push({ id: edgeId, source: step.id, target: nextId, animated: false });
        }
      });
    }
  });

  return autoLayout(nodes, edges);
}

/**
 * dagre 기반 자동 레이아웃
 */
export function autoLayout(nodes: Node[], edges: Edge[]): { nodes: Node[]; edges: Edge[] } {
  if (nodes.length === 0) return { nodes, edges };

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: "TB", nodesep: 60, ranksep: 80 });

  nodes.forEach((node) => {
    g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  });
  edges.forEach((edge) => {
    g.setEdge(edge.source, edge.target);
  });

  dagre.layout(g);

  const layoutedNodes = nodes.map((node) => {
    const pos = g.node(node.id);
    return {
      ...node,
      position: { x: pos.x - NODE_WIDTH / 2, y: pos.y - NODE_HEIGHT / 2 },
    };
  });

  return { nodes: layoutedNodes, edges };
}
