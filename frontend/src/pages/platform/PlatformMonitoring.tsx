import { COLORS, FONTS } from "../../theme";
import { Badge } from "../../components/common/Badge";
import { StatCard } from "../../components/common/StatCard";
import { DataTable, type Column } from "../../components/common/DataTable";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { EmptyState } from "../../components/common/EmptyState";
import { PageHeader } from "../../components/layout/PageHeader";
import { usePlatformUsage } from "../../hooks/usePlatform";
import type { TenantUsage } from "../../types/tenant";

export default function PlatformMonitoring() {
  const { data: usage, isLoading } = usePlatformUsage();

  const tenantUsages: TenantUsage[] = usage?.tenantUsages ?? [];
  const maxCost = Math.max(...tenantUsages.map((t) => t.costUsd ?? 0), 1);

  const columns: Column<TenantUsage>[] = [
    {
      header: "테넌트",
      render: (t) => (
        <div>
          <div style={{ fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>{t.tenantName ?? t.tenantId}</div>
          <div style={{ fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textDim }}>{t.tenantId}</div>
        </div>
      ),
    },
    {
      header: "요청 수",
      render: (t) => (
        <span style={{ fontFamily: FONTS.mono }}>{(t.requestCount ?? 0).toLocaleString()}</span>
      ),
      width: "100px",
    },
    {
      header: "토큰",
      render: (t) => (
        <span style={{ fontFamily: FONTS.mono }}>{(t.tokenCount ?? 0).toLocaleString()}</span>
      ),
      width: "110px",
    },
    {
      header: "비용",
      render: (t) => (
        <span style={{ fontFamily: FONTS.mono, color: COLORS.purple }}>
          ${(t.costUsd ?? 0).toFixed(4)}
        </span>
      ),
      width: "100px",
    },
    {
      header: "상태",
      render: (t) => (
        <Badge color={t.status === "active" ? "success" : t.status === "suspended" ? "warning" : "muted"}>
          {t.status ?? "active"}
        </Badge>
      ),
      width: "90px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader title="플랫폼 현황" subtitle="Super Admin — 전체 테넌트 사용량 총괄" />

      {/* Stat Cards */}
      <div style={{ display: "flex", gap: 16, marginBottom: 28, flexWrap: "wrap" }}>
        <StatCard
          label="활성 테넌트"
          value={usage?.activeTenants ?? "—"}
          sub="전체 등록 테넌트"
          color={COLORS.accent}
        />
        <StatCard
          label="오늘 총 요청"
          value={usage?.totalRequestsToday?.toLocaleString() ?? "—"}
          sub="플랫폼 전체 합산"
          color={COLORS.success}
        />
        <StatCard
          label="오늘 총 비용"
          value={usage?.totalCostTodayUsd != null ? `$${Number(usage.totalCostTodayUsd).toFixed(2)}` : "—"}
          sub="LLM API 비용 합계"
          color={COLORS.purple}
        />
        <StatCard
          label="평균 응답시간"
          value={usage?.avgLatencyMs != null ? `${Math.round(usage.avgLatencyMs)}ms` : "—"}
          sub="전체 테넌트 평균"
          color={COLORS.warning}
        />
      </div>

      {/* Tenant Usage Table */}
      {tenantUsages.length === 0 ? (
        <EmptyState icon="🌐" title="테넌트 사용량 데이터가 없습니다" description="테넌트가 생성되고 사용되면 데이터가 표시됩니다" />
      ) : (
        <>
          <div style={{ marginBottom: 24 }}>
            <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 12 }}>
              테넌트별 사용량
            </div>
            <DataTable columns={columns} data={tenantUsages} keyExtractor={(t) => t.tenantId} />
          </div>

          {/* Bar chart */}
          <div
            style={{
              background: COLORS.surface,
              border: `1px solid ${COLORS.border}`,
              borderRadius: 12,
              padding: 20,
            }}
          >
            <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 16 }}>
              테넌트별 비용 비교
            </div>
            {tenantUsages.map((t, i) => {
              const pct = ((t.costUsd ?? 0) / maxCost) * 100;
              const barColors = [COLORS.accent, COLORS.purple, COLORS.success, COLORS.warning, COLORS.danger];
              return (
                <div key={t.tenantId} style={{ marginBottom: 14 }}>
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                    <span style={{ fontSize: 12, fontFamily: FONTS.sans, color: COLORS.text }}>
                      {t.tenantName ?? t.tenantId}
                    </span>
                    <span style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted }}>
                      ${(t.costUsd ?? 0).toFixed(4)} · {t.requestCount?.toLocaleString() ?? 0}건
                    </span>
                  </div>
                  <div style={{ height: 8, background: COLORS.surfaceActive, borderRadius: 4, overflow: "hidden" }}>
                    <div
                      style={{
                        height: "100%",
                        borderRadius: 4,
                        width: `${pct}%`,
                        background: barColors[i % barColors.length],
                        transition: "width 0.8s ease",
                      }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
