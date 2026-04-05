import { useState } from "react";
import { cn } from "@/lib/utils";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface TimelineStep {
  id: string;
  name: string;
  type: string;
  startedAt?: string;
  completedAt?: string;
  durationMs?: number;
  status?: "completed" | "failed" | "running" | "pending";
}

interface RunTimelineProps {
  steps: TimelineStep[];
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

const STATUS_COLORS: Record<string, string> = {
  completed: "bg-green-500 dark:bg-green-600",
  failed: "bg-red-500 dark:bg-red-600",
  running: "bg-blue-500 dark:bg-blue-600 animate-pulse",
  pending: "bg-gray-300 dark:bg-gray-600",
};

const STATUS_LABELS: Record<string, string> = {
  completed: "완료",
  failed: "실패",
  running: "실행 중",
  pending: "대기",
};

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export function RunTimeline({ steps }: RunTimelineProps) {
  const [hoveredId, setHoveredId] = useState<string | null>(null);

  const totalMs = steps.reduce((sum, s) => sum + (s.durationMs ?? 0), 0) || 1;

  return (
    <div className="flex flex-col gap-2">
      {/* Bar */}
      <div className="flex w-full h-8 rounded-lg overflow-hidden border border-border bg-muted/30">
        {steps.map((step) => {
          const widthPct = Math.max(((step.durationMs ?? 0) / totalMs) * 100, 2);
          const status = step.status ?? "pending";

          return (
            <div
              key={step.id}
              className={cn(
                "relative h-full flex items-center justify-center text-[10px] font-medium text-white cursor-pointer transition-opacity",
                STATUS_COLORS[status],
                hoveredId && hoveredId !== step.id && "opacity-50",
              )}
              style={{ width: `${widthPct}%`, minWidth: "8px" }}
              onMouseEnter={() => setHoveredId(step.id)}
              onMouseLeave={() => setHoveredId(null)}
            >
              {widthPct > 10 && (
                <span className="truncate px-1">{step.name}</span>
              )}

              {/* Tooltip */}
              {hoveredId === step.id && (
                <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 z-50 pointer-events-none">
                  <div className="bg-popover text-popover-foreground border border-border rounded-lg shadow-lg px-3 py-2 whitespace-nowrap text-xs">
                    <div className="font-medium">{step.name}</div>
                    <div className="text-muted-foreground mt-0.5">
                      {STATUS_LABELS[status]}
                      {step.durationMs != null && ` \u00B7 ${formatDuration(step.durationMs)}`}
                    </div>
                    <div className="text-muted-foreground/70 text-[10px] mt-0.5">{step.type}</div>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Legend */}
      <div className="flex items-center gap-4 text-xs text-muted-foreground">
        {(["completed", "failed", "running", "pending"] as const).map((s) => (
          <span key={s} className="flex items-center gap-1.5">
            <span className={cn("inline-block w-2.5 h-2.5 rounded-sm", STATUS_COLORS[s])} />
            {STATUS_LABELS[s]}
          </span>
        ))}
        <span className="ml-auto font-mono">{formatDuration(totalMs)}</span>
      </div>
    </div>
  );
}
