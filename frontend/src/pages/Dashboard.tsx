import { cn } from "@/lib/utils";

import { Badge } from "../components/common/Badge";
import { StatCard } from "../components/common/StatCard";
import { ActionButton } from "../components/common/ActionButton";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { useDashboard, useActionLogs, useApprovals, useApprove, useReject, useUsage } from "../hooks/useAdmin";
import type { ActionLog, Approval, UsageStat } from "../types/admin";

function statusColor(status: string): "success" | "warning" | "danger" | "purple" | "muted" {
  if (status === "success") return "success";
  if (status === "warning" || status === "pending") return "warning";
  if (status === "failure" || status === "danger") return "danger";
  if (status === "purple") return "purple";
  return "muted";
}

function statusIcon(status: string) {
  if (status === "success") return "✓";
  if (status === "warning" || status === "pending") return "⏳";
  if (status === "failure" || status === "danger") return "✕";
  return "🛡";
}

const modelColors = ["hsl(var(--primary))", "hsl(var(--info))", "hsl(var(--warning))", "hsl(var(--success))"];

export default function Dashboard() {
  const { data: stats, isLoading: statsLoading } = useDashboard();
  const { data: logs = [], isLoading: logsLoading } = useActionLogs();
  const { data: approvals = [], isLoading: approvalsLoading } = useApprovals();
  const { data: usageRaw } = useUsage();
  const approve = useApprove();
  const reject = useReject();

  // Extract model usage list
  const usageList: UsageStat[] = (() => {
    if (!usageRaw) return [];
    if (Array.isArray(usageRaw)) return usageRaw;
    if ("content" in (usageRaw as { content?: unknown }))
      return (usageRaw as { content: UsageStat[] }).content ?? [];
    return [];
  })();

  const maxCount = Math.max(...usageList.map((u) => u.requestCount ?? 0), 1);

  return (
    <Page
      actions={
        <>
          <ActionButton variant="ghost" icon="📊" small>리포트</ActionButton>
          <ActionButton variant="ghost" icon="⚙️" small>설정</ActionButton>
        </>
      }
    >

      {/* Stat Cards */}
      <div className="flex gap-4 mb-7 flex-wrap">
        {statsLoading ? (
          <div className="flex-1 flex justify-center py-5">
            <LoadingSpinner />
          </div>
        ) : (
          <>
            <StatCard label="오늘 토큰" value={stats?.tokens_today?.toLocaleString() ?? "—"} sub="LLM 총 사용량" color="hsl(var(--primary))" />
            <StatCard label="활성 연결" value={stats?.active_connections ?? "—"} sub="연결된 외부 시스템" color="hsl(var(--success))" />
            <StatCard label="승인 대기" value={stats?.pending_approvals ?? "—"} sub="즉각 처리 필요" color="hsl(var(--warning))" />
            <StatCard label="오늘 비용" value={stats?.cost_today_usd != null ? `$${Number(stats.cost_today_usd).toFixed(2)}` : "—"} sub="LLM API 비용" color="hsl(var(--info))" />
          </>
        )}
      </div>

      <div className="grid grid-cols-[1fr_380px] gap-5">
        {/* Action Log */}
        <div className="bg-card border border-border rounded-xl overflow-hidden">
          <div className="px-5 py-4 border-b border-border flex justify-between items-center">
            <span className="text-sm font-semibold text-foreground">실시간 액션 로그</span>
            <Badge color="success" pulse>LIVE</Badge>
          </div>
          <div className="max-h-[440px] overflow-y-auto">
            {logsLoading ? (
              <LoadingSpinner fullPage />
            ) : logs.length === 0 ? (
              <div className="p-8 text-center text-muted-foreground/60 font-mono text-[13px]">
                액션 로그가 없습니다
              </div>
            ) : (
              logs.map((log: ActionLog, i) => (
                <div
                  key={log.id ?? i}
                  className={cn("px-5 py-3 border-b border-border/5 flex gap-3 items-start", i === 0 && "animate-fade-in")}
                >
                  <span className="text-[11px] font-mono text-muted-foreground/60 min-w-[45px] pt-0.5">
                    {log.executedAt ? new Date(log.executedAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }) : "--:--"}
                  </span>
                  <Badge color={statusColor(log.status)}>
                    {statusIcon(log.status)}
                  </Badge>
                  <div className="flex-1">
                    <div className="text-[13px] text-foreground mb-0.5">
                      {log.message ?? log.actionType ?? log.adapterId ?? "액션"}
                    </div>
                    <div className="text-[11px] font-mono text-muted-foreground/60">
                      {log.actions ?? (log.durationMs != null ? `${log.durationMs}ms` : "")}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Right Panel */}
        <div className="flex flex-col gap-5">
          {/* Model Usage */}
          <div className="bg-card border border-border rounded-xl p-5">
            <div className="text-sm font-semibold text-foreground mb-4">모델별 사용량</div>
            {usageList.length === 0 ? (
              <div className="text-xs text-muted-foreground/60 font-mono text-center py-3">
                사용 데이터 없음
              </div>
            ) : (
              usageList.slice(0, 5).map((m, i) => (
                <div key={m.modelId ?? i} className="mb-3.5">
                  <div className="flex justify-between mb-1.5">
                    <span className="text-xs text-foreground">
                      {m.modelName ?? m.modelId ?? `Model ${i + 1}`}
                    </span>
                    <span className="text-[11px] font-mono text-muted-foreground">
                      {m.requestCount ?? 0}건
                      {m.costUsd != null ? ` · $${Number(m.costUsd).toFixed(2)}` : ""}
                    </span>
                  </div>
                  <div className="h-1.5 bg-accent rounded-sm overflow-hidden">
                    <div
                      className="h-full rounded-sm transition-[width] duration-1000 ease-out"
                      style={{
                        width: `${Math.round(((m.requestCount ?? 0) / maxCount) * 100)}%`,
                        background: modelColors[i % modelColors.length],
                      }}
                    />
                  </div>
                </div>
              ))
            )}
          </div>

          {/* Pending Approvals */}
          <div className="bg-card border border-warning/20 rounded-xl p-5">
            <div className="flex justify-between items-center mb-4">
              <span className="text-sm font-semibold text-foreground">승인 대기</span>
              <Badge color="warning">{approvals.length}건</Badge>
            </div>
            {approvalsLoading ? (
              <LoadingSpinner fullPage />
            ) : approvals.length === 0 ? (
              <div className="text-xs text-muted-foreground/60 font-mono text-center py-2">
                대기 중인 승인 없음
              </div>
            ) : (
              approvals.slice(0, 5).map((item: Approval) => (
                <div key={item.id} className="p-2.5 px-3 rounded-lg mb-2 bg-accent border border-border">
                  <div className="flex justify-between mb-1.5">
                    <span className="text-xs font-mono text-primary">
                      {item.workflowRunId ?? item.id}
                    </span>
                    {item.amount != null && (
                      <span className="text-xs font-mono text-warning">
                        {item.currency ?? "₩"}{item.amount.toLocaleString()}
                      </span>
                    )}
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-[11px] text-muted-foreground/60">
                      {item.approver ?? item.requestedBy ?? "승인자"}
                      {item.requestedAt ? ` · ${new Date(item.requestedAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })} 요청` : ""}
                    </span>
                    <div className="flex gap-1">
                      <ActionButton
                        variant="success"
                        small
                        icon="✓"
                        disabled={approve.isPending}
                        onClick={() => approve.mutate({ id: item.id })}
                      >
                        승인
                      </ActionButton>
                      <ActionButton
                        variant="danger"
                        small
                        icon="✕"
                        disabled={reject.isPending}
                        onClick={() => reject.mutate({ id: item.id })}
                      >
                        거부
                      </ActionButton>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </Page>
  );
}
