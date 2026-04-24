import { useState } from "react";
import {
  Bot,
  Search,
  ClipboardList,
  BookOpen,
  ShieldCheck,
  Loader2,
  CheckCircle2,
  XCircle,
  Clock,
  ChevronDown,
  ChevronRight,
} from "lucide-react";

interface SubagentBlockProps {
  runId: string;
  agentType: string;
  description: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | "TIMEOUT" | "CANCELLED";
  summary?: string;
  durationMs?: number;
}

/**
 * CR-053 Phase 4: 서브에이전트 실행 전용 블록.
 * 일반 도구(Wrench 아이콘)와 시각적으로 구분 — 에이전트 타입별 아이콘 + 실행 시간 표시.
 */
export const SubagentBlock = ({
  runId,
  agentType,
  description,
  status,
  summary,
  durationMs,
}: SubagentBlockProps) => {
  const [summaryOpen, setSummaryOpen] = useState(false);

  const TypeIcon =
    agentType === "EXPLORE"
      ? Search
      : agentType === "PLAN"
      ? ClipboardList
      : agentType === "GUIDE"
      ? BookOpen
      : agentType === "VERIFICATION"
      ? ShieldCheck
      : Bot;

  const StatusIcon =
    status === "RUNNING"
      ? Loader2
      : status === "COMPLETED"
      ? CheckCircle2
      : status === "TIMEOUT"
      ? Clock
      : XCircle;

  const statusClass =
    status === "RUNNING"
      ? "text-blue-500 animate-spin"
      : status === "COMPLETED"
      ? "text-green-600"
      : status === "TIMEOUT"
      ? "text-amber-500"
      : "text-destructive";

  const typeLabel =
    agentType.charAt(0) + agentType.slice(1).toLowerCase();

  const durationLabel = durationMs
    ? durationMs >= 1000
      ? `${(durationMs / 1000).toFixed(1)}s`
      : `${durationMs}ms`
    : null;

  return (
    <div className="rounded border border-blue-200 bg-blue-50/40 text-xs dark:border-blue-900 dark:bg-blue-950/20">
      <div className="flex items-center gap-1.5 px-2 py-1.5">
        <TypeIcon className="h-3.5 w-3.5 text-blue-600 dark:text-blue-400" />
        <span className="font-medium text-blue-900 dark:text-blue-200">
          {typeLabel} Subagent
        </span>
        <span className="text-muted-foreground">·</span>
        <span className="truncate text-muted-foreground" title={description}>
          {description}
        </span>
        <StatusIcon className={`ml-auto h-3 w-3 shrink-0 ${statusClass}`} />
        {durationLabel && (
          <span className="font-mono text-[10px] text-muted-foreground">
            {durationLabel}
          </span>
        )}
        <span
          className="font-mono text-[10px] text-muted-foreground"
          title={runId}
        >
          {runId.slice(-6)}
        </span>
      </div>

      {summary && (
        <>
          <button
            type="button"
            onClick={() => setSummaryOpen((v) => !v)}
            className="flex w-full items-center gap-1 border-t border-blue-200/60 px-2 py-1 text-[10px] text-muted-foreground hover:text-foreground dark:border-blue-900/60"
          >
            {summaryOpen ? (
              <ChevronDown className="h-3 w-3" />
            ) : (
              <ChevronRight className="h-3 w-3" />
            )}
            <span>{status === "COMPLETED" ? "summary" : "detail"}</span>
            {!summaryOpen && (
              <span className="truncate opacity-70">
                {summary.length > 80 ? summary.slice(0, 80) + "…" : summary}
              </span>
            )}
          </button>
          {summaryOpen && (
            <pre
              className={`overflow-x-auto border-t border-blue-200/60 px-2 py-1 text-[10px] whitespace-pre-wrap dark:border-blue-900/60 ${
                status === "COMPLETED"
                  ? "bg-background/60"
                  : "bg-destructive/10"
              }`}
            >
              {summary}
            </pre>
          )}
        </>
      )}
    </div>
  );
};
