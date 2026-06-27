import type { Node, Edge } from "@xyflow/react";
import type { WorkflowStep, WorkflowRequest } from "../../../types/workflow";

/** FE 팔레트 타입 → BE StepType 매핑 */
const TYPE_MAP: Record<string, string> = {
  llm: "LLM_CALL",
  tool: "TOOL_CALL",
  condition: "CONDITION",
  parallel: "PARALLEL",
  approval: "HUMAN_INPUT",
  action: "ACTION",
  agent: "AGENT_CALL",
  evaluator_loop: "EVALUATOR_LOOP",
  sub_workflow: "SUB_WORKFLOW",
  foreach: "FOREACH",
  large_input: "LARGE_INPUT",
};

/** BE StepType → FE 팔레트 타입 (TYPE_MAP 역방향, body 펼치기용) */
export const BE_TO_FE_TYPE: Record<string, string> = Object.fromEntries(
  Object.entries(TYPE_MAP).map(([fe, be]) => [be, fe])
);

/**
 * React Flow 노드/엣지 → WorkflowRequest JSON 변환
 */
export function flowToWorkflow(
  nodes: Node[],
  edges: Edge[],
  meta: { id: string; name: string; description?: string; trigger?: string; status?: string; inputSchema?: Record<string, unknown>; outputSchema?: Record<string, unknown> }
): WorkflowRequest {
  // body 접기: parentId 있는 서브노드는 독립 step 으로 내보내지 않고
  // 부모 FOREACH 의 config.body 로 합친다 (BE 는 body 를 단일 중첩 객체로 받음).
  const childByParent = new Map<string, Node>();
  nodes.forEach((n) => {
    if (n.parentId) childByParent.set(n.parentId, n);
  });
  const topLevelNodes = nodes.filter((n) => !n.parentId);

  const steps: WorkflowStep[] = topLevelNodes.map((node) => {
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

    const config: Record<string, unknown> = {
      ...((node.data.config as Record<string, unknown>) ?? {}),
    };

    // FOREACH 그룹: 서브노드(body)를 config.body 로 접는다.
    const child = childByParent.get(node.id);
    if (child) {
      config.body = {
        type: (TYPE_MAP[child.data.type as string] ?? child.data.type) as string,
        config: (child.data.config as Record<string, unknown>) ?? {},
      };
    }

    return {
      id: node.id,
      name: (node.data.label as string) ?? node.id,
      type: (TYPE_MAP[node.data.type as string] ?? node.data.type) as WorkflowStep["type"],
      config,
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
    inputSchema: meta.inputSchema,
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
