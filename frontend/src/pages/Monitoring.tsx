import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { StatCard } from "../components/common/StatCard";
import { DataTable, type Column } from "../components/common/DataTable";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
import { useUsage } from "../hooks/useAdmin";
import { useModels } from "../hooks/useMonitoring";
import type { UsageStat } from "../types/admin";

export default function Monitoring() {
  const { data: usageRaw, isLoading: usageLoading } = useUsage();
  const { data: models = [] } = useModels();

  const usageList: UsageStat[] = (() => {
    if (!usageRaw) return [];
    if (Array.isArray(usageRaw)) return usageRaw;
    if ("content" in (usageRaw as { content?: unknown }))
      return (usageRaw as { content: UsageStat[] }).content ?? [];
    return [];
  })();

  const totalCost = usageList.reduce((s, u) => s + (u.costUsd ?? 0), 0);
  const totalTokens = usageList.reduce((s, u) => s + (u.tokenCount ?? 0), 0);
  const totalRequests = usageList.reduce((s, u) => s + (u.requestCount ?? 0), 0);
  const avgLatency = usageList.length > 0
    ? usageList.reduce((s, u) => s + (u.avgLatencyMs ?? 0), 0) / usageList.length
    : 0;

  const columns: Column<UsageStat>[] = [
    {
      header: "모델",
      render: (u) => (
        <span style={{ fontFamily: FONTS.mono, color: COLORS.accent, fontSize: 13 }}>
          {u.modelName ?? u.modelId ?? "—"}
        </span>
      ),
    },
    {
      header: "요청 수",
      render: (u) => (
        <span style={{ fontFamily: FONTS.mono }}>{(u.requestCount ?? 0).toLocaleString()}</span>
      ),
      width: "100px",
    },
    {
      header: "토큰",
      render: (u) => (
        <span style={{ fontFamily: FONTS.mono }}>{(u.tokenCount ?? 0).toLocaleString()}</span>
      ),
      width: "110px",
    },
    {
      header: "비용",
      render: (u) => (
        <span style={{ fontFamily: FONTS.mono, color: COLORS.purple }}>
          ${(u.costUsd ?? 0).toFixed(4)}
        </span>
      ),
      width: "100px",
    },
    {
      header: "평균 응답시간",
      render: (u) => (
        <span style={{ fontFamily: FONTS.mono }}>{u.avgLatencyMs != null ? `${u.avgLatencyMs}ms` : "—"}</span>
      ),
      width: "120px",
    },
    {
      header: "성공률",
      render: (u) =>
        u.successRate != null ? (
          <Badge color={u.successRate >= 95 ? "success" : u.successRate >= 80 ? "warning" : "danger"}>
            {u.successRate.toFixed(1)}%
          </Badge>
        ) : (
          <span style={{ color: COLORS.textDim }}>—</span>
        ),
      width: "90px",
    },
  ];

  if (usageLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader title="모니터링" subtitle="비용 추적 및 모델 성능" />

      {/* Stat Cards */}
      <div style={{ display: "flex", gap: 16, marginBottom: 28, flexWrap: "wrap" }}>
        <StatCard label="총 비용 (오늘)" value={`$${totalCost.toFixed(2)}`} sub="LLM API 총액" color={COLORS.purple} />
        <StatCard label="총 토큰" value={totalTokens.toLocaleString()} sub="Input + Output 합계" color={COLORS.accent} />
        <StatCard label="총 요청" value={totalRequests.toLocaleString()} sub="모든 모델 합산" color={COLORS.success} />
        <StatCard label="평균 응답시간" value={avgLatency > 0 ? `${avgLatency.toFixed(0)}ms` : "—"} sub="전체 평균" color={COLORS.warning} />
      </div>

      {/* Model Performance Table */}
      <div style={{ marginBottom: 24 }}>
        <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 12 }}>
          모델별 성능
        </div>
        <DataTable
          columns={columns}
          data={usageList}
          keyExtractor={(u) => u.modelId ?? Math.random().toString()}
          emptyMessage="사용량 데이터가 없습니다"
        />
      </div>

      {/* Cost bar chart */}
      {usageList.length > 0 && (
        <div
          style={{
            background: COLORS.surface,
            border: `1px solid ${COLORS.border}`,
            borderRadius: 12,
            padding: 20,
          }}
        >
          <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 16 }}>
            모델별 비용 비교
          </div>
          {usageList.map((u, i) => {
            const pct = totalCost > 0 ? ((u.costUsd ?? 0) / totalCost) * 100 : 0;
            const barColors = [COLORS.accent, COLORS.purple, COLORS.warning, COLORS.success, COLORS.danger];
            return (
              <div key={u.modelId ?? i} style={{ marginBottom: 14 }}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                  <span style={{ fontSize: 12, fontFamily: FONTS.sans, color: COLORS.text }}>
                    {u.modelName ?? u.modelId ?? `Model ${i + 1}`}
                  </span>
                  <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted }}>
                    ${(u.costUsd ?? 0).toFixed(4)} · {pct.toFixed(1)}%
                  </span>
                </div>
                <div style={{ height: 6, background: COLORS.surfaceActive, borderRadius: 3, overflow: "hidden" }}>
                  <div
                    style={{
                      height: "100%",
                      borderRadius: 3,
                      width: `${pct}%`,
                      background: barColors[i % barColors.length],
                      transition: "width 1s ease",
                    }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Available Models */}
      {models.length > 0 && (
        <div style={{ marginTop: 24 }}>
          <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 12 }}>
            사용 가능한 모델
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {models.map((m) => (
              <div
                key={m.id}
                style={{
                  padding: "8px 14px",
                  borderRadius: 8,
                  background: COLORS.surface,
                  border: `1px solid ${COLORS.border}`,
                  fontSize: 12,
                  fontFamily: FONTS.mono,
                  color: COLORS.text,
                }}
              >
                {m.name ?? m.id}
                {m.provider && (
                  <span style={{ color: COLORS.textDim, marginLeft: 6 }}>({m.provider})</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
