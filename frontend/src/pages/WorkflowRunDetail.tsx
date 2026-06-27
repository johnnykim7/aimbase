import { useLayoutEffect, useState } from "react";
import { Link, useParams, useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { workflowsApi } from "../api/workflows";
import {
  AlertTriangle,
  ArrowDownToLine,
  Ban,
  Bot,
  CheckCircle2,
  RotateCcw,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  Flag,
  History,
  Play,
  RefreshCw,
  Wrench,
  XCircle,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge, type BadgeColor } from "../components/common/Badge";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { useSetHeaderOverride } from "../components/layout/AppShell";
import { useWorkflows } from "../hooks/useWorkflows";
import { useConnections } from "../hooks/useConnections";
import {
  useWorkflowRun,
  useWorkflowRunEvents,
  useWorkflowRunEvent,
} from "../hooks/useWorkflowRuns";
import { RUN_STATUS_COLORS, formatDuration } from "./WorkflowRuns";
import type { WorkflowRunEvent, WorkflowRunEventType } from "../types/workflow";
import { RunChatFlow } from "../components/workflow/RunChatFlow";
import { runEventsToChat } from "../lib/runEventsToChat";

const EVENT_VISUALS: Record<WorkflowRunEventType, { icon: LucideIcon; color: BadgeColor }> = {
  STEP_START:   { icon: Play,          color: "muted" },
  TOOL_USE:     { icon: Wrench,        color: "accent" },
  TOOL_RESULT:  { icon: CheckCircle2,  color: "success" },
  LLM_REQUEST:  { icon: ArrowDownToLine, color: "accent" },
  LLM_RESPONSE: { icon: Bot,           color: "purple" },
  STEP_END:     { icon: Flag,          color: "success" },
  STEP_FAILED:  { icon: AlertTriangle, color: "danger" },
};

/** 본문 전문이 있을 수 있는 이벤트만 펼침 허용 (CR-102 적재 대상과 동일). */
const EXPANDABLE: WorkflowRunEventType[] = [
  "LLM_REQUEST",
  "LLM_RESPONSE",
  "TOOL_USE",
  "TOOL_RESULT",
  "STEP_END",
];

function payloadSummary(e: WorkflowRunEvent): string {
  const p = e.payload ?? {};
  switch (e.event_type) {
    case "STEP_START":
      return String(p.step_type ?? "");
    case "LLM_RESPONSE":
      return [p.model, p.in_tok != null ? `${p.in_tok}→${p.out_tok} tok` : null]
        .filter(Boolean)
        .join(" · ");
    case "TOOL_USE":
      return String(p.input_preview ?? "");
    case "TOOL_RESULT":
      return p.ok === false
        ? `실패 · ${p.error ?? ""}`
        : `${p.output_size ?? 0} chars`;
    case "STEP_END":
      return `${p.output_size ?? 0} chars`;
    case "STEP_FAILED":
      return String(p.error ?? "");
    default:
      return "";
  }
}

function BodySection({ label, text }: { label: string; text: string }) {
  return (
    <div>
      <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">
        {label}
      </div>
      <pre className="whitespace-pre-wrap break-words text-xs font-mono bg-muted/50 border border-border rounded-lg p-3 max-h-96 overflow-auto m-0">
        {text}
      </pre>
    </div>
  );
}

/** 펼침 시에만 단건 본문을 fetch — run 전체 본문 일괄 로드는 수 MB 가 될 수 있음 (CR-102). */
function EventBody({ runId, eventId }: { runId: string; eventId: number }) {
  const { data: full, isLoading } = useWorkflowRunEvent(runId, eventId, true);

  if (isLoading) {
    return (
      <div className="flex justify-center py-4">
        <LoadingSpinner />
      </div>
    );
  }
  if (!full) return null;

  const sections: React.ReactNode[] = [];
  if (full.prompt_text)
    sections.push(<BodySection key="prompt" label="Prompt (입력 전문)" text={full.prompt_text} />);
  if (full.response_text)
    sections.push(<BodySection key="response" label="Response (응답 전문)" text={full.response_text} />);
  if (full.input_json)
    sections.push(
      <BodySection key="input" label="Tool Input (전문)" text={JSON.stringify(full.input_json, null, 2)} />
    );
  if (full.output_text)
    sections.push(<BodySection key="output" label="Output (결과 전문)" text={full.output_text} />);

  if (sections.length === 0) {
    return (
      <div className="text-xs text-muted-foreground/60 py-2">
        본문이 적재되지 않은 이벤트입니다 (CR-102 이전 실행이거나 메타 전용 이벤트).
      </div>
    );
  }
  return <div className="flex flex-col gap-3 py-2">{sections}</div>;
}

function EventRow({ runId, event, stepModel }: { runId: string; event: WorkflowRunEvent; stepModel?: string }) {
  const [expanded, setExpanded] = useState(false);
  const visual = EVENT_VISUALS[event.event_type] ?? EVENT_VISUALS.STEP_START;
  const failed = event.event_type === "STEP_FAILED" || event.payload?.ok === false;
  const Icon = failed && event.event_type === "TOOL_RESULT" ? XCircle : visual.icon;
  const expandable = EXPANDABLE.includes(event.event_type);
  // CR-108: subagent_run_id 가 채워진 이벤트 = AGENT_CALL 안에서 에이전트가 자율로 부른 도구/응답.
  // 워크플로우 step 이 직접 부른 도구(subagent_run_id=null)와 시각적으로 구분해야 "누가 부른 건지" 헷갈리지 않는다.
  const isAgentChild = !!event.subagent_run_id;
  // STEP_START 행에 그 스텝이 사용한 모델 동반 표시 (같은 step_id 의 LLM_RESPONSE 에서 집계)
  const summary =
    event.event_type === "STEP_START" && stepModel
      ? `${payloadSummary(event)} · ${stepModel}`
      : payloadSummary(event);

  return (
    <div
      className={cn(
        "border-b border-border last:border-b-0",
        // CR-108: 에이전트 자식 행은 왼쪽 강조 보더 + 살짝 음영 — 부모 step 에 종속된 내부 작업임을 표시
        isAgentChild && "border-l-2 border-l-purple-400 bg-purple-50/30 dark:bg-purple-950/10"
      )}
    >
      <div
        onClick={expandable ? () => setExpanded((v) => !v) : undefined}
        className={cn(
          "flex items-center gap-3 px-4 py-2.5",
          // CR-108: 에이전트 자식은 들여쓰기로 계층 표현
          isAgentChild && "pl-10",
          expandable && "cursor-pointer hover:bg-accent/50"
        )}
      >
        {expandable ? (
          expanded ? (
            <ChevronDown className="size-3.5 text-muted-foreground shrink-0" />
          ) : (
            <ChevronRight className="size-3.5 text-muted-foreground shrink-0" />
          )
        ) : (
          <span className="w-3.5 shrink-0" />
        )}
        <Icon className={cn("size-4 shrink-0", failed ? "text-destructive" : "text-muted-foreground")} />
        <Badge color={failed ? "danger" : visual.color}>{event.event_type}</Badge>
        {/* CR-108: 에이전트가 부른 것임을 한눈에 — 봇 배지 */}
        {isAgentChild && (
          <span
            className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300 shrink-0"
            title={`에이전트가 호출 (subagent ${event.subagent_run_id?.slice(0, 8)})`}
          >
            <Bot className="size-3" />
            에이전트
          </span>
        )}
        <span className="text-xs font-mono text-foreground shrink-0">
          {event.step_id ?? "--"}
          {event.iteration != null && <span className="text-muted-foreground/60">[{event.iteration}]</span>}
        </span>
        {event.tool_name && (
          <span className="text-xs font-mono text-primary shrink-0">{event.tool_name}</span>
        )}
        <span className="text-xs text-muted-foreground truncate flex-1">{summary}</span>
        {event.duration_ms != null && (
          <span className="text-[11px] font-mono text-muted-foreground/60 shrink-0">
            {event.duration_ms}ms
          </span>
        )}
        <span className="text-[11px] font-mono text-muted-foreground/60 shrink-0">
          {event.created_at
            ? new Date(event.created_at).toLocaleTimeString("ko-KR", { hour12: false })
            : ""}
        </span>
      </div>
      {expanded && (
        <div className="px-11 pb-3">
          <EventBody runId={runId} eventId={event.id} />
        </div>
      )}
    </div>
  );
}

function MetaItem({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div className="text-[10px] font-mono text-muted-foreground/60 uppercase tracking-wider mb-1">
        {label}
      </div>
      <div className="text-sm text-foreground">{value}</div>
    </div>
  );
}

export default function WorkflowRunDetail() {
  const { runId } = useParams<{ runId: string }>();
  const setHeaderOverride = useSetHeaderOverride();
  const [view, setView] = useState<"timeline" | "chat">("chat");
  // 상단 메타카드 접기 — 접으면 본문(흐름 카드)이 그 높이만큼 확장
  const [metaCollapsed, setMetaCollapsed] = useState(false);
  const { data: run, isLoading: runLoading, refetch: refetchRun, isFetching: runFetching } = useWorkflowRun(runId);
  // CR-108: 진행중 run 이면 본문 전문 일괄 + 2초 폴링 (채팅 흐름이 실시간으로 차오르게).
  // 종료된 run 은 폴링 없이 1회 + 본문 일괄 (채팅 흐름 사후 재구성).
  const isRunning = run ? !["completed", "failed", "cancelled"].includes(run.status) : false;
  const {
    data: events = [],
    isLoading: eventsLoading,
    refetch: refetchEvents,
    isFetching: eventsFetching,
  } = useWorkflowRunEvents(runId, {
    includeBody: true,
    refetchInterval: isRunning ? 2000 : 0,
  });
  const { data: workflows = [] } = useWorkflows();
  const { data: connections = [] } = useConnections();
  const refreshing = runFetching || eventsFetching;
  const refresh = () => { refetchRun(); refetchEvents(); };

  // CR-116: 강제 취소 / 재실행
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const forceCancel = useMutation({
    mutationFn: () => workflowsApi.cancelRun(runId!, true),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["workflow-run", runId] });
      refetchRun();
    },
  });
  const rerun = useMutation({
    mutationFn: () => workflowsApi.rerunRun(runId!),
    onSuccess: (res) => {
      const newRunId = res.data.data?.id;
      if (newRunId) navigate(`/workflows/runs/${newRunId}`);
    },
  });
  // 커넥터 id → name (UI 는 ID 직접 노출 금지 — name 으로 표시)
  const connNames = new Map(connections.map((c) => [c.id, c.name]));

  // "모델 · 커넥터명" 라벨 — LLM_RESPONSE payload 의 model/connection_id 결합
  const modelConnLabel = (ev: WorkflowRunEvent): string | undefined => {
    const model = typeof ev.payload?.model === "string" ? ev.payload.model : undefined;
    if (!model) return undefined;
    const connId = typeof ev.payload?.connection_id === "string" ? ev.payload.connection_id : undefined;
    const connName = connId ? connNames.get(connId) ?? connId : undefined;
    return connName ? `${model} · ${connName}` : model;
  };

  // 사용 모델/커넥터 요약 — run 전체 distinct (등장 순서 유지)
  const usedModels = [
    ...new Set(
      events
        .filter((e) => e.event_type === "LLM_RESPONSE")
        .map(modelConnLabel)
        .filter((m): m is string => !!m)
    ),
  ];
  // 스텝별 모델/커넥터 — STEP_START 행 동반 표시용 (같은 step_id 의 첫 LLM_RESPONSE 기준)
  const stepModels = new Map<string, string>();
  for (const e of events) {
    if (e.event_type !== "LLM_RESPONSE" || !e.step_id) continue;
    const label = modelConnLabel(e);
    if (label && !stepModels.has(e.step_id)) stepModels.set(e.step_id, label);
  }
  const workflowName = run
    ? workflows.find((w) => w.id === run.workflowId)?.name ?? run.workflowId
    : "";

  useLayoutEffect(() => {
    if (run) {
      setHeaderOverride({
        title: `실행 상세 — ${workflowName}`,
        subtitle: `Run ${run.id.slice(0, 8)} · ${run.status}`,
      });
    }
    return () => setHeaderOverride(null);
  }, [run, workflowName, setHeaderOverride]);

  if (runLoading) return <LoadingSpinner fullPage />;
  if (!run) {
    return <EmptyState icon={<History className="size-6" />} title="실행을 찾을 수 없습니다" />;
  }

  const errorText =
    run.error == null
      ? null
      : typeof run.error === "string"
        ? run.error
        : JSON.stringify(run.error, null, 2);

  return (
    <Page
      actions={
        <div className="flex items-center gap-2">
          <button
            onClick={refresh}
            disabled={refreshing}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-border text-xs font-medium text-foreground hover:bg-accent disabled:opacity-50 disabled:cursor-default cursor-pointer bg-card"
          >
            <RefreshCw className={cn("size-3.5", refreshing && "animate-spin")} />
            새로고침
          </button>
          {/* CR-116: 진행 중이면 강제 취소(CLI worker 즉시 kill) */}
          {isRunning && (
            <button
              onClick={() => {
                if (confirm("이 실행을 강제로 취소하시겠어요?\n진행 중인 CLI worker 를 즉시 종료하고 실행을 cancelled 로 끝냅니다.")) {
                  forceCancel.mutate();
                }
              }}
              disabled={forceCancel.isPending}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-destructive/40 text-xs font-medium text-destructive hover:bg-destructive/10 disabled:opacity-50 disabled:cursor-default cursor-pointer bg-card"
            >
              <Ban className={cn("size-3.5", forceCancel.isPending && "animate-pulse")} />
              {forceCancel.isPending ? "취소 중…" : "강제 취소"}
            </button>
          )}
          {/* CR-116: 종료된 실행이면 같은 입력으로 재실행 */}
          {!isRunning && (
            <button
              onClick={() => rerun.mutate()}
              disabled={rerun.isPending}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-border text-xs font-medium text-foreground hover:bg-accent disabled:opacity-50 disabled:cursor-default cursor-pointer bg-card"
            >
              <RotateCcw className={cn("size-3.5", rerun.isPending && "animate-spin")} />
              {rerun.isPending ? "재실행 중…" : "재실행"}
            </button>
          )}
        </div>
      }
    >
      {/* 화면 높이를 메타카드(고정) + 흐름 카드(남은 공간)로 분할 — 바깥 스크롤 없이 본문만 카드 안에서 스크롤 */}
      <div className="flex flex-col h-full">
      {/* Meta Card — 접기 가능 (접으면 본문이 그 높이만큼 확장) */}
      {!metaCollapsed && (
      <div className="bg-card border border-border rounded-xl p-5 mb-5 shrink-0">
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
          <MetaItem
            label="워크플로우"
            value={
              <Link to={`/workflows/${run.workflowId}`} className="text-primary no-underline hover:underline">
                {workflowName}
              </Link>
            }
          />
          <MetaItem
            label="상태"
            value={
              <Badge color={RUN_STATUS_COLORS[run.status] ?? "muted"} pulse={run.status === "running"}>
                {run.status}
              </Badge>
            }
          />
          <MetaItem
            label="시작"
            value={run.startedAt ? new Date(run.startedAt).toLocaleString("ko-KR") : "--"}
          />
          <MetaItem
            label="완료"
            value={run.completedAt ? new Date(run.completedAt).toLocaleString("ko-KR") : "--"}
          />
          <MetaItem label="소요" value={formatDuration(run.startedAt, run.completedAt)} />
          <MetaItem
            label="사용 모델 · 커넥터"
            value={
              usedModels.length > 0 ? (
                <span className="font-mono text-xs">{usedModels.join(", ")}</span>
              ) : (
                <span className="text-muted-foreground/60">--</span>
              )
            }
          />
          <MetaItem
            label="Run ID"
            value={<span className="font-mono text-xs">{run.id}</span>}
          />
        </div>
        {errorText && (
          <div className="mt-4">
            <BodySection label="Error" text={errorText} />
          </div>
        )}
      </div>
      )}

      {/* CR-108: 흐름(채팅식) ↔ 타임라인(구조) 탭 전환 */}
      <div className="bg-card border border-border rounded-xl overflow-hidden flex flex-col flex-1 min-h-0">
        <div className="px-4 py-3 border-b border-border flex items-center gap-2 flex-wrap shrink-0">
          <div className="inline-flex rounded-lg border border-border p-0.5">
            <button
              onClick={() => setView("chat")}
              className={cn(
                "px-3 py-1 rounded-md text-xs font-medium cursor-pointer",
                view === "chat" ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground",
              )}
            >
              흐름
            </button>
            <button
              onClick={() => setView("timeline")}
              className={cn(
                "px-3 py-1 rounded-md text-xs font-medium cursor-pointer",
                view === "timeline" ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground",
              )}
            >
              타임라인
            </button>
          </div>
          <span className="text-xs text-muted-foreground">({events.length}건)</span>
          <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300">
            <Bot className="size-3" />
            에이전트
          </span>
          <span className="text-muted-foreground/60 text-xs">
            = AGENT_CALL 안에서 에이전트가 자율로 호출한 도구 (워크플로우 단계가 직접 부른 도구와 구분)
          </span>
          {/* 상단 메타카드 접기/펼치기 — 본문을 더 크게 보기 */}
          <button
            onClick={() => setMetaCollapsed((v) => !v)}
            className="ml-auto inline-flex items-center gap-1 px-2 py-1 rounded-md border border-border text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-accent cursor-pointer"
            title={metaCollapsed ? "상단 정보 펼치기" : "상단 정보 접고 본문 크게 보기"}
          >
            {metaCollapsed ? <ChevronDown className="size-3.5" /> : <ChevronUp className="size-3.5" />}
            {metaCollapsed ? "펼치기" : "크게 보기"}
          </button>
        </div>

        {/* 본문만 자체 세로 스크롤 — 남은 높이를 채우고(flex-1) 카드 안에서 스크롤바를 잡고 이동 가능 (탭 헤더는 고정) */}
        <div className="flex-1 min-h-0 overflow-y-auto">
          {eventsLoading ? (
            <div className="flex justify-center py-10">
              <LoadingSpinner />
            </div>
          ) : events.length === 0 ? (
            <div className="p-8 text-center text-muted-foreground/60 text-sm">
              기록된 이벤트가 없습니다 (CR-090 이전 실행이거나 이벤트 기록 실패)
            </div>
          ) : view === "chat" ? (
            <RunChatFlow steps={runEventsToChat(events, connNames)} live={isRunning} />
          ) : (
            events.map((e) => (
              <EventRow
                key={e.id}
                runId={run.id}
                event={e}
                stepModel={e.step_id ? stepModels.get(e.step_id) : undefined}
              />
            ))
          )}
        </div>
      </div>
      </div>
    </Page>
  );
}
