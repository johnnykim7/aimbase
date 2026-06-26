import { useEffect, useState } from "react";
import { AlertTriangle, Bot, CheckCircle2, ChevronRight, Loader2, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import { ToolUseBlock } from "../chat/blocks/ToolUseBlock";
import { TextBlock } from "../chat/blocks/TextBlock";
import type { ChatFlowBlock, ChatFlowIteration, ChatFlowStep } from "../../lib/runEventsToChat";

interface RunChatFlowProps {
  steps: ChatFlowStep[];
  /** 진행중 run 이면 마지막 running step 에 spinner 표시 */
  live?: boolean;
}

function durationLabel(ms?: number): string | null {
  if (ms == null) return null;
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

/** step 타입별 "작업 중" 문구. CLI 경로는 thinking(💭) 본문을 받지 못하므로(CLI stream-json
 *  thinking 누락 버그), 빈 화면 대신 무슨 일이 진행 중인지 맥락 문구 + 경과 타이머로 안내한다. */
function workingLabel(stepType?: string): string {
  switch (stepType) {
    case "AGENT_CALL":
      return "에이전트가 작업 중…";
    case "LLM_CALL":
      return "모델이 응답을 생성 중…";
    case "TOOL_CALL":
      return "도구를 실행 중…";
    case "FOREACH":
      return "반복 항목을 처리 중…";
    default:
      return "진행 중…";
  }
}

/**
 * 진행 중(running) step 하단에 표시하는 라이브 인디케이터.
 * 1초마다 경과 시간을 갱신해 "멈춘 게 아니라 일하는 중"임을 보여준다.
 * (CLI 경로 thinking 본문 부재 시의 공백 메움 — A안)
 */
const WorkingIndicator = ({ stepType }: { stepType?: string }) => {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const startedAt = Date.now();
    const id = setInterval(() => {
      setElapsed(Math.floor((Date.now() - startedAt) / 1000));
    }, 1000);
    return () => clearInterval(id);
  }, []);

  return (
    <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <Loader2 className="size-3 animate-spin" />
      <span>{workingLabel(stepType)}</span>
      {elapsed > 0 && (
        <span className="font-mono text-[11px] text-muted-foreground/60">
          {elapsed >= 60 ? `${Math.floor(elapsed / 60)}m ${elapsed % 60}s` : `${elapsed}s`} 경과
        </span>
      )}
    </div>
  );
};

/** 블록 1개 렌더 — text / tool_use(에이전트 호출 구분). step 본문·iteration 본문 공용. */
const FlowBlock = ({ b, idx }: { b: ChatFlowBlock; idx: number }) => {
  if (b.kind === "text") {
    return <TextBlock text={b.text} />;
  }
  // tool_use — isAgent 면 들여쓰기 + 보라 보더 + 봇 배지로 "에이전트 호출" 구분
  return (
    <div
      className={cn(
        b.isAgent && "ml-4 border-l-2 border-l-purple-400 pl-3 dark:border-l-purple-600",
      )}
    >
      {b.isAgent && (
        <div className="mb-1 inline-flex items-center gap-1 rounded bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700 dark:bg-purple-900/40 dark:text-purple-300">
          <Bot className="size-3" />
          에이전트가 호출
        </div>
      )}
      <ToolUseBlock id={b.id} name={b.name} input={b.input} result={b.result} key={idx} />
    </div>
  );
};

/** iteration 헤더 요약 — "도구 N · 응답 M" 형태로 무슨 일을 했는지 한 줄 표기. */
function summarizeBlocks(blocks: ChatFlowBlock[]): string {
  const tools = blocks.filter((b) => b.kind === "tool_use");
  const texts = blocks.filter((b) => b.kind === "text");
  const parts: string[] = [];
  if (tools.length) {
    // 도구명 중복 제거해 앞 3개만 미리보기
    const names = Array.from(new Set(tools.map((b) => (b.kind === "tool_use" ? b.name : "")))).filter(
      Boolean,
    );
    const preview = names.slice(0, 3).join(", ") + (names.length > 3 ? " …" : "");
    parts.push(`도구 ${tools.length} (${preview})`);
  }
  if (texts.length) parts.push(`응답 ${texts.length}`);
  return parts.length ? parts.join(" · ") : "본문 없음";
}

/** CR-117: running 자식 대기 상태를 사람이 읽는 문구로. */
function waitLabel(it: ChatFlowIteration): string | null {
  if (it.status !== "running") return null;
  if (it.waitState === "awaiting_model") return "모델 응답 대기 중…";
  if (it.waitState === "running_tool") return "도구 실행 중…";
  return "진행 중…";
}

/**
 * CR-117: 빈 응답 경고 배지 여부.
 * - status=failed + 에러 텍스트가 빈응답 사유면(드물게 STEP_FAILED 가 온 경우) true.
 * - 또는 구조 추정(emptyResponseSuspected) — FOREACH 자식 STEP_FAILED 부재 케이스.
 */
function isEmptyResponseFailure(it: ChatFlowIteration): boolean {
  if (it.emptyResponseSuspected) return true;
  if (it.status !== "failed") return false;
  return it.blocks.some(
    (b) => b.kind === "text" && /empty model response|no text, no structured/i.test(b.text),
  );
}

/** FOREACH 한 반복(반복 #N) — 기본 접힘, 헤더에 상태·요약. 클릭 시 자식 블록 펼침. */
const IterationGroup = ({ it }: { it: ChatFlowIteration }) => {
  const [open, setOpen] = useState(false);
  const Icon =
    it.status === "ok" ? CheckCircle2 : it.status === "failed" ? XCircle : Loader2;
  const iconClass =
    it.status === "ok"
      ? "text-green-600"
      : it.status === "failed"
        ? "text-destructive"
        : "text-blue-500 animate-spin";
  const dur = durationLabel(it.durationMs);
  const wait = waitLabel(it);
  const emptyResp = isEmptyResponseFailure(it);

  return (
    <div className="rounded-md border border-border/70 bg-muted/20">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2 px-2.5 py-1.5 text-left hover:bg-muted/40"
      >
        <ChevronRight
          className={cn("size-3.5 shrink-0 transition-transform", open && "rotate-90")}
        />
        <Icon className={cn("size-3.5 shrink-0", iconClass)} />
        <span className="font-mono text-xs font-medium text-foreground">반복 #{it.index}</span>
        {it.pdfPagesRange && (
          <span className="shrink-0 rounded bg-blue-100 px-1.5 py-0.5 text-[10px] font-medium text-blue-700 dark:bg-blue-900/40 dark:text-blue-300">
            p.{it.pdfPagesRange}
          </span>
        )}
        {/* CR-117: 빈 응답 확정(재시도 상한 후 FAILED) 경고 배지 */}
        {emptyResp && (
          <span className="inline-flex shrink-0 items-center gap-0.5 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">
            <AlertTriangle className="size-3" />빈 응답
          </span>
        )}
        {/* CR-117: running 대기 상태 문구 — 도구만 끝나고 전체 끝난 것으로 오인 방지 */}
        {wait ? (
          <span className="truncate text-[11px] font-medium text-blue-600 dark:text-blue-400">
            {wait}
          </span>
        ) : (
          <span className="truncate text-[11px] text-muted-foreground">
            {summarizeBlocks(it.blocks)}
          </span>
        )}
        {dur && (
          <span className="ml-auto shrink-0 font-mono text-[11px] text-muted-foreground/60">
            {dur}
          </span>
        )}
      </button>
      {open && (
        <div className="flex flex-col gap-2 border-t border-border/70 p-2.5">
          {it.blocks.length === 0 && (
            <div className="text-xs text-muted-foreground/50">(본문 없음)</div>
          )}
          {it.blocks.map((b, i) => (
            <FlowBlock key={i} b={b} idx={i} />
          ))}
        </div>
      )}
    </div>
  );
};

/**
 * CR-108: 워크플로우 run 을 클로드코드식 채팅 흐름으로 렌더.
 * 채팅 화면의 ToolUseBlock / TextBlock 을 그대로 재사용한다 (UI 일관성 + 재발명 방지).
 * AGENT_CALL 안에서 에이전트가 부른 도구(isAgent)는 들여쓰기 + 보라 보더 + 봇 배지로 구분.
 */
export const RunChatFlow = ({ steps, live }: RunChatFlowProps) => {
  if (steps.length === 0) {
    return (
      <div className="p-8 text-center text-sm text-muted-foreground/60">
        표시할 흐름이 없습니다 (CR-090 이전 실행이거나 본문이 적재되지 않음)
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 p-4">
      {steps.map((step, si) => {
        const StatusIcon =
          step.status === "ok" ? CheckCircle2 : step.status === "failed" ? XCircle : Loader2;
        const statusClass =
          step.status === "ok"
            ? "text-green-600"
            : step.status === "failed"
              ? "text-destructive"
              : "text-blue-500 animate-spin";
        const isLastRunning = live && step.status === "running" && si === steps.length - 1;
        const dur = durationLabel(step.durationMs);

        return (
          <div key={step.stepId} className="rounded-lg border border-border bg-card">
            {/* Step 헤더 — step 이름 + 타입 + 모델·커넥션 + 상태 */}
            <div className="flex items-center gap-2 border-b border-border px-3 py-2">
              <StatusIcon className={cn("size-4 shrink-0", statusClass)} />
              <span className="font-mono text-sm font-medium text-foreground">{step.stepId}</span>
              {step.stepType && (
                <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
                  {step.stepType}
                </span>
              )}
              {/* 사용자 요구: 모델·커넥션명 표시 */}
              {step.model && (
                <span className="font-mono text-[11px] text-purple-600 dark:text-purple-400">
                  {step.model}
                  {step.connection && (
                    <span className="text-muted-foreground"> · {step.connection}</span>
                  )}
                </span>
              )}
              {dur && (
                <span className="ml-auto font-mono text-[11px] text-muted-foreground/60">{dur}</span>
              )}
            </div>

            {/* 블록 흐름 */}
            <div className="flex flex-col gap-2 p-3">
              {step.blocks.length === 0 &&
                !step.iterations?.length &&
                !isLastRunning && (
                  <div className="text-xs text-muted-foreground/50">(본문 없음)</div>
                )}
              {step.blocks.map((b, i) => (
                <FlowBlock key={b.kind === "tool_use" ? b.id : i} b={b} idx={i} />
              ))}
              {/* FOREACH 자식(반복) 그룹 — 각 반복이 무슨 도구/응답을 냈는지 접힘 카드로 */}
              {step.iterations?.length ? (
                <div className="flex flex-col gap-1.5">
                  <div className="text-[11px] font-medium text-muted-foreground">
                    {step.iterations.length}개 반복
                  </div>
                  {step.iterations.map((it) => (
                    <IterationGroup key={it.index} it={it} />
                  ))}
                </div>
              ) : null}
              {isLastRunning && <WorkingIndicator stepType={step.stepType} />}
            </div>
          </div>
        );
      })}
    </div>
  );
};
