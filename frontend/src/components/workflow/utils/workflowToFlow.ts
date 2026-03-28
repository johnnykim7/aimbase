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
  const edgeSet = new Set<string>();
  const addEdge = (source: string, target: string) => {
    const edgeId = `${source}->${target}`;
    if (!edgeSet.has(edgeId)) {
      edgeSet.add(edgeId);
      edges.push({ id: edgeId, source, target, animated: false });
    }
  };

  const stepIds = new Set(steps.map((s) => s.id));
  let hasAnyLink = false;

  steps.forEach((step) => {
    // dependsOn 기반 엣지
    const deps = step.dependsOn ?? [];
    deps.forEach((depId) => {
      if (stepIds.has(depId)) {
        addEdge(depId, step.id);
        hasAnyLink = true;
      }
    });

    // nextSteps 기반 엣지
    if (step.nextSteps) {
      step.nextSteps.forEach((nextId) => {
        if (stepIds.has(nextId)) {
          addEdge(step.id, nextId);
          hasAnyLink = true;
        }
      });
    }

    // BE onSuccess/onFailure 기반 엣지
    const raw = step as unknown as Record<string, unknown>;
    if (typeof raw.onSuccess === "string" && stepIds.has(raw.onSuccess as string)) {
      addEdge(step.id, raw.onSuccess as string);
      hasAnyLink = true;
    }
    if (typeof raw.onFailure === "string" && stepIds.has(raw.onFailure as string)) {
      addEdge(step.id, raw.onFailure as string);
      hasAnyLink = true;
    }
  });

  // 어떤 연결도 없으면 순서대로 연결
  if (!hasAnyLink && steps.length > 1) {
    for (let i = 0; i < steps.length - 1; i++) {
      addEdge(steps[i].id, steps[i + 1].id);
    }
  }

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
