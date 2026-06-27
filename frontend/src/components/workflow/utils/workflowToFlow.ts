import type { Node, Edge } from "@xyflow/react";
import dagre from "dagre";
import type { Workflow } from "../../../types/workflow";
import { BE_TO_FE_TYPE } from "./flowToWorkflow";

const NODE_WIDTH = 200;
const NODE_HEIGHT = 60;

// FOREACH 그룹 노드 / 그 안의 body 서브노드 레이아웃 상수
const GROUP_PADDING_TOP = 56;   // 그룹 헤더 높이
const GROUP_PADDING_X = 16;
const GROUP_PADDING_BOTTOM = 16;
const CHILD_WIDTH = 200;
const CHILD_HEIGHT = 60;

/**
 * WorkflowEntity → React Flow 노드/엣지 변환
 */
export function workflowToFlow(workflow: Workflow): { nodes: Node[]; edges: Edge[] } {
  const steps = workflow.steps ?? [];
  if (steps.length === 0) return { nodes: [], edges: [] };

  const nodes: Node[] = [];
  const childNodes: Node[] = []; // 그룹 서브노드는 부모 뒤에 push (React Flow 는 부모가 먼저 와야 함)

  steps.forEach((step, i) => {
    const cfg = (step.config ?? {}) as Record<string, unknown>;
    const body = cfg.body as { type?: string; config?: Record<string, unknown> } | undefined;
    const isForeachGroup = step.type === "FOREACH" && body != null && typeof body === "object";

    if (isForeachGroup) {
      // body 를 부모 config 에서 분리 — 서브노드로 펼치고, 저장 시 flowToWorkflow 가 다시 접는다.
      const { body: _body, ...parentConfig } = cfg;
      void _body;
      nodes.push({
        id: step.id,
        type: "foreachGroup",
        position: { x: 0, y: i * 220 },
        style: { width: CHILD_WIDTH + GROUP_PADDING_X * 2, height: GROUP_PADDING_TOP + CHILD_HEIGHT + GROUP_PADDING_BOTTOM },
        data: { label: step.name, type: step.type, config: parentConfig, hasBody: true },
      });
      const childType = BE_TO_FE_TYPE[body!.type ?? ""] ?? (body!.type ?? "llm");
      childNodes.push({
        id: `${step.id}__body`,
        type: "workflowNode",
        parentId: step.id,
        extent: "parent",
        position: { x: GROUP_PADDING_X, y: GROUP_PADDING_TOP },
        data: { label: childLabel(childType), type: childType, config: body!.config ?? {} },
      });
    } else {
      nodes.push({
        id: step.id,
        type: "workflowNode",
        position: { x: 0, y: i * 120 },
        data: { label: step.name, type: step.type, config: cfg },
      });
    }
  });

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

  // 그룹 서브노드는 autoLayout(dagre) 대상에서 제외 — 부모 상대좌표 고정 후 합친다.
  const laid = autoLayout(nodes, edges);
  return { nodes: [...laid.nodes, ...childNodes], edges: laid.edges };
}

/** body 서브노드 표시 라벨 (FE 팔레트 라벨과 일치) */
function childLabel(feType: string): string {
  const map: Record<string, string> = {
    llm: "LLM 호출",
    tool: "도구 실행",
    agent: "에이전트",
    sub_workflow: "서브 워크플로우",
    large_input: "대용량 입력",
  };
  return map[feType] ?? feType;
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
