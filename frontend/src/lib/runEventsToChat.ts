import type { WorkflowRunEvent } from "../types/workflow";

/**
 * CR-108: 워크플로우 run 이벤트(TOOL_USE/TOOL_RESULT/LLM_RESPONSE/STEP_*)를
 * "클로드코드식 채팅 흐름"으로 재구성하기 위한 블록/메시지 모델.
 *
 * 채팅의 {@link StreamBlock}(useChatStream)과 호환되는 형태이되, run 전용 메타
 * (model / connection / 에이전트 자식 여부)를 추가로 싣는다 — 사용자 요구:
 * "커넥션 이름, 모델 이름 등을 표시".
 */

export type ChatFlowBlock =
  | {
      kind: "text";
      text: string;
      model?: string;
      connection?: string;
      /** "input": LLM_CALL 에 우리가 넣은 입력 프롬프트. undefined/"output": 모델 응답. */
      role?: "input" | "output";
    }
  | {
      kind: "tool_use";
      id: string;
      name: string;
      input: Record<string, unknown>;
      result?: { output: string; isError: boolean };
      /** AGENT_CALL 안에서 에이전트가 자율로 부른 도구 (subagent_run_id 채워짐) */
      isAgent: boolean;
      durationMs?: number;
    };

/**
 * FOREACH 컨테이너 step 의 한 iteration(반복 #N).
 * 자식 body 의 step_id 는 `부모[index]` 형태로 적재되므로(ForeachStepExecutor.withItemId),
 * 그 index 로 묶어 각 반복이 무슨 도구/응답을 냈는지 그룹으로 보여준다.
 */
export interface ChatFlowIteration {
  /** 0-based 반복 인덱스 */
  index: number;
  /** 자식 body step_id (예: download_attachments[0]) — 표시·디버깅용 */
  childStepId: string;
  blocks: ChatFlowBlock[];
  status: "running" | "ok" | "failed";
  durationMs?: number;
  /**
   * CR-117: running 자식이 무엇을 기다리는지 — FE 가 사람이 읽는 문구로 표시.
   * - "awaiting_model": 마지막이 도구(결과 도착) → 모델 응답 대기 중 (도구 후 LLM_RESPONSE 미도착).
   *   사용자 우려: "스피너 없이 도구만 끝나 전체 끝난 것으로 오인" → 이 상태를 명시한다.
   * - "running_tool": 마지막이 결과 없는 도구 → 도구 실행 중.
   * - undefined: running 아님.
   */
  waitState?: "awaiting_model" | "running_tool";
  /**
   * CR-117: PDF 자동분할 청크 메타(있으면 "파일 X — 청크 2/12" 표시).
   * 자식 step_id 에 청크 정보가 없으므로, 본 필드는 BE 가 결과에 실어줄 때만 채워진다(후속).
   */
  pdfPagesRange?: string;
  /**
   * CR-117: 빈 응답 의심 — 도구는 끝났는데(마지막 블록=결과 있는 도구) 그 뒤 LLM_RESPONSE 가
   * 영영 안 오고 부모 FOREACH 가 종료된 케이스. FOREACH 자식엔 STEP_FAILED 가 안 와서 에러
   * 텍스트가 없으므로, 이 구조 신호(도구 후 텍스트 0 + 부모 종료)로 빈응답을 추정해 경고 배지.
   */
  emptyResponseSuspected?: boolean;
}

export interface ChatFlowStep {
  /** step_id (없으면 "--") */
  stepId: string;
  /** STEP_START payload.step_type (TOOL_CALL / AGENT_CALL / LLM_CALL / FOREACH …) */
  stepType?: string;
  blocks: ChatFlowBlock[];
  status: "running" | "ok" | "failed";
  durationMs?: number;
  /** 이 step 에서 사용한 모델·커넥션 (LLM_RESPONSE 에서 집계, 헤더 표시용) */
  model?: string;
  connection?: string;
  /** FOREACH 자식(반복) 그룹 — `부모[index]` step_id 이벤트를 index 별로 묶은 것 */
  iterations?: ChatFlowIteration[];
}

function str(v: unknown): string | undefined {
  return typeof v === "string" && v.length > 0 ? v : undefined;
}

/** (step_id, iteration, tool_name) 페어링 키 — TOOL_USE ↔ TOOL_RESULT 매칭. */
function toolKey(e: WorkflowRunEvent): string {
  return `${e.step_id ?? "-"}::${e.iteration ?? "-"}::${e.tool_name ?? "-"}`;
}

/**
 * 이벤트 배열(시간순)을 step 단위 채팅 흐름으로 변환.
 *
 * - step 순서는 이벤트 등장 순서(첫 등장 기준) 유지.
 * - TOOL_RESULT 는 직전 같은 키의 TOOL_USE 블록에 result 로 병합 (없으면 단독 표시 생략).
 * - subagent_run_id 가 있으면 isAgent=true → FE 가 들여쓰기/배지로 "에이전트 호출" 구분.
 * - connNames: connection_id → 표시명 매핑 (UI 는 ID 직접 노출 금지).
 */
export function runEventsToChat(
  events: WorkflowRunEvent[],
  connNames?: Map<string, string>,
): ChatFlowStep[] {
  const steps: ChatFlowStep[] = [];
  const stepByKey = new Map<string, ChatFlowStep>();
  // 페어링: toolKey → 해당 step 의 블록 인덱스
  const pendingTool = new Map<string, { step: ChatFlowStep; idx: number }>();

  const getStep = (e: WorkflowRunEvent): ChatFlowStep => {
    const key = e.step_id ?? "--";
    let s = stepByKey.get(key);
    if (!s) {
      s = { stepId: key, blocks: [], status: "running" };
      stepByKey.set(key, s);
      steps.push(s);
    }
    return s;
  };

  for (const e of events) {
    const s = getStep(e);
    const p = e.payload ?? {};
    switch (e.event_type) {
      case "STEP_START":
        s.stepType = str(p.step_type) ?? s.stepType;
        break;

      case "LLM_REQUEST": {
        // 입력 프롬프트 — 우리가 호출 직전에 적재. 응답을 기다리지 않으므로 running 중에도 표시된다.
        const model = str(p.model);
        const connId = str(p.connection_id);
        const connection = connId ? connNames?.get(connId) ?? connId : undefined;
        if (model && !s.model) s.model = model;
        if (connection && !s.connection) s.connection = connection;
        const text = str(e.prompt_text);
        if (text) {
          s.blocks.push({ kind: "text", text, model, connection, role: "input" });
        }
        break;
      }

      case "LLM_RESPONSE": {
        const model = str(p.model);
        const connId = str(p.connection_id);
        const connection = connId ? connNames?.get(connId) ?? connId : undefined;
        if (model && !s.model) s.model = model;
        if (connection && !s.connection) s.connection = connection;
        const text = str(e.response_text);
        if (text) {
          s.blocks.push({ kind: "text", text, model, connection, role: "output" });
        }
        break;
      }

      case "TOOL_USE": {
        const block: ChatFlowBlock = {
          kind: "tool_use",
          id: `${e.id}`,
          name: e.tool_name ?? "tool",
          input: (e.input_json as Record<string, unknown>) ?? {},
          isAgent: !!e.subagent_run_id,
        };
        s.blocks.push(block);
        pendingTool.set(toolKey(e), { step: s, idx: s.blocks.length - 1 });
        break;
      }

      case "TOOL_RESULT": {
        const pending = pendingTool.get(toolKey(e));
        const output = str(e.output_text) ?? "";
        const isError = p.ok === false;
        if (pending) {
          const blk = pending.step.blocks[pending.idx];
          if (blk.kind === "tool_use") {
            blk.result = { output, isError };
            if (e.duration_ms != null) blk.durationMs = e.duration_ms;
          }
          pendingTool.delete(toolKey(e));
        } else {
          // TOOL_USE 를 못 본 결과(드묾) — 단독 블록으로라도 표시
          s.blocks.push({
            kind: "tool_use",
            id: `${e.id}`,
            name: e.tool_name ?? "tool",
            input: {},
            result: { output, isError },
            isAgent: !!e.subagent_run_id,
            durationMs: e.duration_ms ?? undefined,
          });
        }
        break;
      }

      case "STEP_END":
        s.status = "ok";
        if (e.duration_ms != null) s.durationMs = e.duration_ms;
        break;

      case "STEP_FAILED":
        s.status = "failed";
        if (e.duration_ms != null) s.durationMs = e.duration_ms;
        s.blocks.push({ kind: "text", text: `❌ ${str(p.error) ?? "step failed"}` });
        break;
    }
  }

  return foldForeachIterations(steps);
}

/**
 * FOREACH 자식 step_id 를 (부모 컨테이너 id, index)로 분해. 아니면 null.
 *
 * ForeachStepExecutor 는 body step_id 가 명시 안 되면 `부모.body` 를 쓰고
 * (parseBody → parentId+".body"), 거기에 `[index]` 를 붙인다(withItemId).
 * 따라서 자식 step_id 는 `download_attachments.body[3]` 형태 → 끝의 `.body` 를 벗겨
 * 진짜 FOREACH 컨테이너(`download_attachments`)로 귀속시킨다.
 */
function parseChildStepId(stepId: string): { parentId: string; index: number } | null {
  const m = /^(.+)\[(\d+)\]$/.exec(stepId);
  if (!m) return null;
  // body id 가 명시되지 않은 기본 케이스: `.body` 접미사를 벗겨 컨테이너 id 로 환원.
  const parentId = m[1].replace(/\.body$/, "");
  return { parentId, index: Number(m[2]) };
}

/**
 * 자식 반복의 상태 추론 — FOREACH 자식엔 STEP_END(완료 신호)가 안 와 status 가 영구
 * running 으로 남는다. 이벤트만으로 마감 여부를 추정하되, **신뢰 가능한 신호만** 쓴다:
 *
 * - 블록에 에러 결과 → failed
 * - 부모 FOREACH 가 종료(ok/failed) → 자식도 그 상태로 마감
 *   (FOREACH 는 모든 자식 완료 후에야 STEP_END 발행 ← 부모 종료 = 자식 전원 종료 보장)
 * - 마지막 블록이 텍스트 응답(LLM_RESPONSE) → ok
 *   (에이전트가 도구 루프를 끝내고 최종 답을 낸 상태. 도구 뒤 추가 호출이 없음을
 *    "끝이 텍스트"로 판정. 중간 텍스트 후 도구를 더 부르면 끝이 tool 이라 running 유지)
 * - 그 외 → running (도구 호출이 더 이어질 수 있음)
 *
 * ※ "나중 반복이 시작됐으니 이전 반복은 끝" 규칙은 폐기 — extract_facts 처럼 parallel
 *   FOREACH 는 여러 자식이 동시에 돌아 나중 index 등장이 이전 완료를 뜻하지 않는다(오표시).
 */
function inferIterationStatus(
  childStatus: "running" | "ok" | "failed",
  parentStatus: "running" | "ok" | "failed",
  blocks: ChatFlowBlock[],
): "running" | "ok" | "failed" {
  if (childStatus !== "running") return childStatus;
  const hasError = blocks.some((b) => b.kind === "tool_use" && b.result?.isError);
  if (hasError) return "failed";
  if (parentStatus !== "running") return parentStatus;

  // 끝이 응답 텍스트 = 에이전트가 도구 루프를 끝내고 최종 답을 낸 것 → 완료.
  // (도구 호출이 더 이어지면 마지막 블록이 tool_use 라 이 분기를 안 탄다.)
  // ★입력 프롬프트(role==="input")는 "답"이 아니므로 제외 — 입력만으로 완료 판정하면 오표시.
  const lastBlock = blocks[blocks.length - 1];
  if (lastBlock?.kind === "text" && lastBlock.role !== "input") return "ok";

  return "running";
}

/**
 * FOREACH 자식 step(`부모[index]`)을 부모 FOREACH step 의 iterations 로 접어 넣는다.
 *
 * 자식 body 는 별도 step_id(`download_attachments[0]` …)로 적재되므로, 위 grouping 만으로는
 * FOREACH 컨테이너(본문 0개 = "(본문 없음)")와 자식 카드들이 따로 흩어진다. 여기서 자식을
 * 부모로 귀속시켜 "반복 #N 안에서 무슨 도구/응답을 냈는지"를 한 카드로 묶는다.
 *
 * - 부모 step 이 steps 에 없으면(이벤트 누락) 자식들로 컨테이너를 합성한다.
 * - iterations 는 index 오름차순 정렬.
 */
function foldForeachIterations(steps: ChatFlowStep[]): ChatFlowStep[] {
  const byId = new Map<string, ChatFlowStep>();
  for (const s of steps) byId.set(s.stepId, s);

  const result: ChatFlowStep[] = [];
  const seenParents = new Set<string>();

  for (const s of steps) {
    const child = parseChildStepId(s.stepId);
    if (!child) {
      result.push(s);
      continue;
    }

    // 부모 컨테이너 확보 (없으면 합성). 부모는 자식보다 먼저 등장하므로 보통 result 에 이미 있음.
    let parent = byId.get(child.parentId);
    if (!parent) {
      parent = { stepId: child.parentId, stepType: "FOREACH", blocks: [], status: "running" };
      byId.set(child.parentId, parent);
      result.push(parent);
    } else if (!seenParents.has(child.parentId)) {
      // 부모는 result 에 이미 push 됨(원래 순서 유지) — 마킹만.
      seenParents.add(child.parentId);
    }

    (parent.iterations ??= []).push({
      index: child.index,
      childStepId: s.stepId,
      blocks: s.blocks,
      status: s.status,
      durationMs: s.durationMs,
    });
  }

  for (const s of result) {
    if (!s.iterations) continue;
    s.iterations.sort((a, b) => a.index - b.index);
    // 자식엔 STEP_END 가 없으므로 부모 종료 상태/최종 응답 도착으로 status 보정.
    for (const it of s.iterations) {
      it.status = inferIterationStatus(it.status, s.status, it.blocks);
      const last = it.blocks[it.blocks.length - 1];
      // CR-117: running 자식의 대기 상태 — "모델 응답 대기 중" vs "도구 실행 중".
      if (it.status === "running" && last?.kind === "tool_use") {
        it.waitState = last.result ? "awaiting_model" : "running_tool";
      }
      // CR-117: 빈 응답 의심 — 부모 종료(자식 전원 마감) 시점에 마지막이 "결과 있는 도구"이고
      // 텍스트 응답이 한 번도 안 왔으면, 도구만 끝나고 모델 텍스트 0 으로 끝난 빈응답으로 본다.
      // (FOREACH 자식엔 STEP_FAILED 가 없어 에러 텍스트가 없으므로 구조 신호로 추정.)
      // ★단, AGENT_CALL 자식에만 적용 — AGENT_CALL 은 에이전트가 도구 루프 후 텍스트를 내야 정상이라
      //   "텍스트 0"이 빈응답 신호다. TOOL_CALL body 는 도구만 호출하고 텍스트가 없는 게 정상이므로
      //   제외(오탐 방지). AGENT_CALL 식별 = 에이전트 자율 호출 도구(subagent_run_id → isAgent=true) 존재.
      const isAgentChild = it.blocks.some((b) => b.kind === "tool_use" && b.isAgent);
      if (
        isAgentChild &&
        s.status !== "running" &&
        last?.kind === "tool_use" &&
        !!last.result &&
        // 응답 텍스트(role!=="input") 가 한 번도 없을 때만 빈응답. 입력 프롬프트는 "응답"이 아님.
        !it.blocks.some((b) => b.kind === "text" && b.role !== "input")
      ) {
        it.emptyResponseSuspected = true;
      }
    }
  }
  return result;
}
