import type { Node, Edge } from "@xyflow/react";
import type { WorkflowStep, WorkflowRequest } from "../../../types/workflow";

/**
 * React Flow 노드/엣지 → WorkflowRequest JSON 변환
 */
export function flowToWorkflow(
  nodes: Node[],
  edges: Edge[],
  meta: { id: string; name: string; description?: string; trigger?: string; status?: string; outputSchema?: Record<string, unknown> }
): WorkflowRequest {
  const steps: WorkflowStep[] = nodes.map((node) => {
    const incomingEdges = edges.filter((e) => e.target === node.id);
    const dependsOn = incomingEdges.map((e) => e.source);

    const outgoingEdges = edges.filter((e) => e.source === node.id);
    const nextSteps = outgoingEdges.map((e) => e.target);

    const conditionBranches =
      node.data.type === "condition"
        ? outgoingEdges.map((e) => ({
            condition: (e.data?.condition as string) ?? "",
            nextStep: e.target,
          }))
        : undefined;

    return {
      id: node.id,
      name: (node.data.label as string) ?? node.id,
      type: node.data.type as WorkflowStep["type"],
      config: (node.data.config as Record<string, unknown>) ?? {},
      dependsOn,
      nextSteps: nextSteps.length > 0 ? nextSteps : undefined,
      conditionBranches: conditionBranches && conditionBranches.length > 0 ? conditionBranches : undefined,
    };
  });

  return {
    id: meta.id,
    name: meta.name,
    description: meta.description,
    trigger: meta.trigger,
    steps,
    outputSchema: meta.outputSchema,
    status: meta.status,
  };
}

/**
 * DAG 순환 검증
 */
export function hasCycle(nodes: Node[], edges: Edge[]): boolean {
  const adj = new Map<string, string[]>();
  const inDeg = new Map<string, number>();
  nodes.forEach((n) => {
    adj.set(n.id, []);
    inDeg.set(n.id, 0);
  });
  edges.forEach((e) => {
    adj.get(e.source)?.push(e.target);
    inDeg.set(e.target, (inDeg.get(e.target) ?? 0) + 1);
  });

  const queue = [...inDeg.entries()].filter(([, d]) => d === 0).map(([id]) => id);
  let visited = 0;
  while (queue.length > 0) {
    const curr = queue.shift()!;
    visited++;
    for (const next of adj.get(curr) ?? []) {
      const deg = (inDeg.get(next) ?? 1) - 1;
      inDeg.set(next, deg);
      if (deg === 0) queue.push(next);
    }
  }
  return visited !== nodes.length;
}
