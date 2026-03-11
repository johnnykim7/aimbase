import { useState, useEffect } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { StatCard } from "../components/common/StatCard";
import { ActionButton } from "../components/common/ActionButton";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
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

const modelColors = [COLORS.accent, COLORS.purple, COLORS.warning, COLORS.success];

export default function Dashboard() {
  const [time, setTime] = useState(new Date());
  useEffect(() => {
    const t = setInterval(() => setTime(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

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
    <div>
      <PageHeader
        title="대시보드"
        subtitle={`${time.toLocaleTimeString("ko-KR")} · 실시간 업데이트`}
        actions={
          <>
            <ActionButton variant="ghost" icon="📊" small>리포트</ActionButton>
            <ActionButton variant="ghost" icon="⚙️" small>설정</ActionButton>
          </>
        }
      />

      {/* Stat Cards */}
      <div style={{ display: "flex", gap: 16, marginBottom: 28, flexWrap: "wrap" }}>
        {statsLoading ? (
          <div style={{ flex: 1, display: "flex", justifyContent: "center", padding: 20 }}>
            <LoadingSpinner />
          </div>
        ) : (
          <>
            <StatCard
              label="오늘 토큰"
              value={stats?.tokens_today?.toLocaleString() ?? "—"}
              sub="LLM 총 사용량"
              color={COLORS.accent}
            />
            <StatCard
              label="활성 연결"
              value={stats?.active_connections ?? "—"}
              sub="연결된 외부 시스템"
              color={COLORS.success}
            />
            <StatCard
              label="승인 대기"
              value={stats?.pending_approvals ?? "—"}
              sub="즉각 처리 필요"
              color={COLORS.warning}
            />
            <StatCard
              label="오늘 비용"
              value={stats?.cost_today_usd != null ? `$${Number(stats.cost_today_usd).toFixed(2)}` : "—"}
              sub="LLM API 비용"
              color={COLORS.purple}
            />
          </>
        )}
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 380px", gap: 20 }}>
        {/* Action Log */}
        <div
          style={{
            background: COLORS.surface,
            border: `1px solid ${COLORS.border}`,
            borderRadius: 12,
            overflow: "hidden",
          }}
        >
          <div
            style={{
              padding: "16px 20px",
              borderBottom: `1px solid ${COLORS.border}`,
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
            }}
          >
            <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
              실시간 액션 로그
            </span>
            <Badge color="success" pulse>LIVE</Badge>
          </div>
          <div style={{ maxHeight: 440, overflowY: "auto" }}>
            {logsLoading ? (
              <LoadingSpinner fullPage />
            ) : logs.length === 0 ? (
              <div style={{ padding: 32, textAlign: "center", color: COLORS.textDim, fontFamily: FONTS.mono, fontSize: 13 }}>
                액션 로그가 없습니다
              </div>
            ) : (
              logs.map((log: ActionLog, i) => (
                <div
                  key={log.id ?? i}
                  style={{
                    padding: "12px 20px",
                    borderBottom: `1px solid ${COLORS.border}08`,
                    display: "flex",
                    gap: 12,
                    alignItems: "flex-start",
                    animation: i === 0 ? "fadeIn 0.5s ease" : undefined,
                  }}
                >
                  <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim, minWidth: 45, paddingTop: 2 }}>
                    {log.executedAt ? new Date(log.executedAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }) : "--:--"}
                  </span>
                  <Badge color={statusColor(log.status)}>
                    {statusIcon(log.status)}
                  </Badge>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 13, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 2 }}>
                      {log.message ?? log.actionType ?? log.adapterId ?? "액션"}
                    </div>
                    <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim }}>
                      {log.actions ?? (log.durationMs != null ? `${log.durationMs}ms` : "")}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Right Panel */}
        <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
          {/* Model Usage */}
          <div
            style={{
              background: COLORS.surface,
              border: `1px solid ${COLORS.border}`,
              borderRadius: 12,
              padding: 20,
            }}
          >
            <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 16 }}>
              모델별 사용량
            </div>
            {usageList.length === 0 ? (
              <div style={{ fontSize: 12, color: COLORS.textDim, fontFamily: FONTS.mono, textAlign: "center", padding: "12px 0" }}>
                사용 데이터 없음
              </div>
            ) : (
              usageList.slice(0, 5).map((m, i) => (
                <div key={m.modelId ?? i} style={{ marginBottom: 14 }}>
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                    <span style={{ fontSize: 12, fontFamily: FONTS.sans, color: COLORS.text }}>
                      {m.modelName ?? m.modelId ?? `Model ${i + 1}`}
                    </span>
                    <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted }}>
                      {m.requestCount ?? 0}건
                      {m.costUsd != null ? ` · $${Number(m.costUsd).toFixed(2)}` : ""}
                    </span>
                  </div>
                  <div style={{ height: 6, background: COLORS.surfaceActive, borderRadius: 3, overflow: "hidden" }}>
                    <div
                      style={{
                        height: "100%",
                        borderRadius: 3,
                        width: `${Math.round(((m.requestCount ?? 0) / maxCount) * 100)}%`,
                        background: modelColors[i % modelColors.length],
                        transition: "width 1s ease",
                      }}
                    />
                  </div>
                </div>
              ))
            )}
          </div>

          {/* Pending Approvals */}
          <div
            style={{
              background: COLORS.surface,
              border: `1px solid ${COLORS.warning}30`,
              borderRadius: 12,
              padding: 20,
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
              <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
                승인 대기
              </span>
              <Badge color="warning">{approvals.length}건</Badge>
            </div>
            {approvalsLoading ? (
              <LoadingSpinner fullPage />
            ) : approvals.length === 0 ? (
              <div style={{ fontSize: 12, color: COLORS.textDim, fontFamily: FONTS.mono, textAlign: "center", padding: "8px 0" }}>
                대기 중인 승인 없음
              </div>
            ) : (
              approvals.slice(0, 5).map((item: Approval) => (
                <div
                  key={item.id}
                  style={{
                    padding: "10px 12px",
                    borderRadius: 8,
                    marginBottom: 8,
                    background: COLORS.surfaceHover,
                    border: `1px solid ${COLORS.border}`,
                  }}
                >
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                    <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.accent }}>
                      {item.workflowRunId ?? item.id}
                    </span>
                    {item.amount != null && (
                      <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.warning }}>
                        {item.currency ?? "₩"}{item.amount.toLocaleString()}
                      </span>
                    )}
                  </div>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                    <span style={{ fontSize: 11, color: COLORS.textDim }}>
                      {item.approver ?? item.requestedBy ?? "승인자"}
                      {item.requestedAt ? ` · ${new Date(item.requestedAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })} 요청` : ""}
                    </span>
                    <div style={{ display: "flex", gap: 4 }}>
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
    </div>
  );
}
