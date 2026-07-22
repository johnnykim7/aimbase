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
      /**
       * CR-120: 청크 인덱스(LLM_REQUEST/RESPONSE payload.iteration). LARGE_INPUT 스텝은
       * 한 step_id 안에서 청크마다 request/response 를 발행하므로, 이 값으로 청크 카드를 묶는다.
       * undefined = 청크 단위가 아닌 일반 블록.
       */
      chunkIndex?: number;
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
      /** CR-120: 청크 인덱스 (위 text 블록과 동일 용도) */
      chunkIndex?: number;
    };

/**
 * CR-120: LARGE_INPUT 한 청크(페이지 N~M)의 처리 단위.
 * BE 가 청크마다 LLM_REQUEST(프롬프트)+LLM_RESPONSE(결과/실패)를 같은 step_id·동일 iteration
 * 으로 발행한다. FE 는 iteration(=chunkIndex)별로 묶어 "청크 N/M (페이지) ✓완료" 카드를 만든다.
 */
export interface ChatFlowChunk {
  /** 0-based 청크 인덱스 */
  index: number;
  blocks: ChatFlowBlock[];
  status: "running" | "ok" | "failed";
  /** "1-20" 등 — BE 본문 "(페이지 X-Y" 에서 파싱 */
  pageRange?: string;
  /** 전체 청크 수 — BE 본문 "청크 N/M" 에서 파싱 (진행률 표시용) */
  totalChunks?: number;
}

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
  /**
   * CR-120: 이 반복(=LARGE_INPUT step)이 청크 단위로 처리됐으면, 청크 카드들.
   * blocks 를 chunkIndex 별로 묶은 것. 있으면 FE 는 blocks 대신 chunks 를 청크 카드로 렌더한다.
   */
  chunks?: ChatFlowChunk[];
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
  /**
   * CR-120: FOREACH 위임 없이 LARGE_INPUT step 단독으로 청크를 처리하는 경우의 청크 카드.
   * (FOREACH 안이면 chunks 는 ChatFlowIteration 에 붙는다.)
   */
  chunks?: ChatFlowChunk[];
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
          s.blocks.push({
            kind: "text", text, model, connection, role: "input",
            chunkIndex: e.iteration ?? undefined,
          });
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
          s.blocks.push({
            kind: "text", text, model, connection, role: "output",
            chunkIndex: e.iteration ?? undefined,
          });
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

/* ── CR-120: 청크 그룹핑 ─────────────────────────────────────────────────────
 *
 * LARGE_INPUT step 은 한 step_id 안에서 청크마다 LLM_REQUEST(프롬프트)+LLM_RESPONSE
 * (결과/실패)를 발행하며, payload.iteration = 청크 인덱스(또는 Reduce 신호 9000/9001)다.
 * 블록의 chunkIndex 로 묶어 "청크 N/M (페이지) ✓완료" 카드를 만든다.
 *
 * 본문 텍스트(BE LargeInputStepExecutor 가 박은 이모지 헤더)에서 전체 청크 수(M)와
 * 페이지 범위를 파싱한다 — step_id 에는 그 메타가 없으므로 본문이 유일한 소스.
 */

/** "청크 3/13 (페이지 41-60" 형태에서 {n, total, pageRange} 파싱. 못 찾으면 빈 객체. */
function parseChunkMeta(text: string): { n?: number; total?: number; pageRange?: string } {
  const out: { n?: number; total?: number; pageRange?: string } = {};
  const m = /청크\s+(\d+)\/(\d+)/.exec(text);
  if (m) {
    out.n = Number(m[1]);
    out.total = Number(m[2]);
  }
  const pm = /페이지\s+([\d-]+)/.exec(text);
  if (pm) out.pageRange = pm[1];
  return out;
}

/** 한 청크 그룹의 상태 — output(role!=="input") 텍스트가 있으면 ok, 실패 본문이면 failed, 아니면 running. */
function chunkStatusOf(blocks: ChatFlowBlock[]): "running" | "ok" | "failed" {
  const outputs = blocks.filter((b) => b.kind === "text" && b.role === "output");
  // 실패/마지막-실패 본문(❌)이 있으면 failed. 재시도(⚠️)는 아직 진행으로 본다.
  const failed = outputs.some((b) => b.kind === "text" && b.text.startsWith("❌"));
  if (failed) return "failed";
  if (outputs.length > 0) return "ok";
  return "running";
}

/**
 * step 의 블록 중 chunkIndex 가 있는 것들을 청크 카드로 묶는다.
 * chunkIndex 없는 블록은 그대로 남겨(plain) 반환한다(예: Reduce 본문은 iteration 9000+ 라
 * 별개 청크로 잡히지 않게 normalChunk 임계값으로 거른다).
 * @returns { plain: 청크 아닌 블록, chunks: 청크 카드(인덱스 오름차순) }
 */
function groupBlocksIntoChunks(blocks: ChatFlowBlock[]): {
  plain: ChatFlowBlock[];
  chunks: ChatFlowChunk[];
} {
  const plain: ChatFlowBlock[] = [];
  const byChunk = new Map<number, ChatFlowBlock[]>();
  for (const b of blocks) {
    const ci = b.chunkIndex;
    // Reduce 진행 신호(9000/9001)는 청크가 아니라 step 본문으로 둔다.
    if (ci == null || ci >= 9000) {
      plain.push(b);
      continue;
    }
    (byChunk.get(ci) ?? byChunk.set(ci, []).get(ci)!).push(b);
  }
  if (byChunk.size === 0) return { plain: blocks, chunks: [] };

  const chunks: ChatFlowChunk[] = [];
  for (const [index, blks] of byChunk) {
    // 본문에서 total/pageRange 파싱(요청/응답 본문 어디든 들어있음).
    let total: number | undefined;
    let pageRange: string | undefined;
    for (const b of blks) {
      if (b.kind !== "text") continue;
      const meta = parseChunkMeta(b.text);
      if (meta.total != null) total = meta.total;
      if (meta.pageRange) pageRange = meta.pageRange;
    }
    chunks.push({ index, blocks: blks, status: chunkStatusOf(blks), pageRange, totalChunks: total });
  }
  chunks.sort((a, b) => a.index - b.index);
  return { plain, chunks };
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

  // CR-120: FOREACH 위임 없이 LARGE_INPUT 단독 step 도 청크로 묶는다(컨테이너 본문 직접 보유).
  for (const s of result) {
    if (s.iterations) continue; // FOREACH 컨테이너는 아래 루프에서 iteration 별로 처리
    const grouped = groupBlocksIntoChunks(s.blocks);
    if (grouped.chunks.length > 0) {
      s.blocks = grouped.plain;
      s.chunks = grouped.chunks;
    }
  }

  for (const s of result) {
    if (!s.iterations) continue;
    s.iterations.sort((a, b) => a.index - b.index);
    // 자식엔 STEP_END 가 없으므로 부모 종료 상태/최종 응답 도착으로 status 보정.
    for (const it of s.iterations) {
      // CR-120: 이 반복이 청크 단위(LARGE_INPUT)면 청크 카드로 묶고, 상태를 청크 기준으로 안정화.
      //   기존 inferIterationStatus 는 "마지막 블록이 input 이면 running" 이라 청크가 병렬로
      //   섞여 들어오면 ok→running 깜빡임을 유발했다 → 청크 완료/전체 기준으로 대체.
      const grouped = groupBlocksIntoChunks(it.blocks);
      if (grouped.chunks.length > 0) {
        it.blocks = grouped.plain;
        it.chunks = grouped.chunks;
        const done = grouped.chunks.filter((c) => c.status !== "running").length;
        const total = grouped.chunks[0]?.totalChunks ?? grouped.chunks.length;
        // 부모(FOREACH/LARGE_INPUT)가 끝났으면 그 상태로 마감, 아니면 청크 완료 여부로 판정.
        if (s.status !== "running") it.status = s.status;
        else it.status = done >= total && total > 0 ? "ok" : "running";
        continue; // 청크 모드는 아래 일반 보정/빈응답 추정을 건너뛴다.
      }
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
