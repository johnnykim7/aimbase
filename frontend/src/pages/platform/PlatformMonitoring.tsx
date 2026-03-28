import { Badge } from "../../components/common/Badge";
import { StatCard } from "../../components/common/StatCard";
import { DataTable, type Column } from "../../components/common/DataTable";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { EmptyState } from "../../components/common/EmptyState";
import { Page } from "../../components/layout/Page";
import { Globe } from "lucide-react";
import { usePlatformUsage } from "../../hooks/usePlatform";
import type { TenantUsage } from "../../types/tenant";

const BAR_COLORS = ["#2563eb", "#7c3aed", "#059669", "#d97706", "#dc2626"];

export default function PlatformMonitoring() {
  const { data: usage, isLoading } = usePlatformUsage();

  const tenantUsages: TenantUsage[] = usage?.tenantUsages ?? [];
  const maxCost = Math.max(...tenantUsages.map((t) => t.costUsd ?? 0), 1);

  const columns: Column<TenantUsage>[] = [
    {
      header: "테넌트",
      render: (t) => (
        <div>
          <div className="font-semibold text-foreground">{t.tenantName ?? t.tenantId}</div>
          <div className="font-mono text-[11px] text-muted-foreground/40">{t.tenantId}</div>
        </div>
      ),
    },
    {
      header: "요청 수",
      render: (t) => (
        <span className="font-mono">{(t.requestCount ?? 0).toLocaleString()}</span>
      ),
      width: "100px",
    },
    {
      header: "토큰",
      render: (t) => (
        <span className="font-mono">{(t.tokenCount ?? 0).toLocaleString()}</span>
      ),
      width: "110px",
    },
    {
      header: "비용",
      render: (t) => (
        <span className="font-mono text-info">
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
    <Page>

      {/* Stat Cards */}
      <div className="flex gap-4 mb-7 flex-wrap">
        <StatCard
          label="활성 테넌트"
          value={usage?.activeTenants ?? "—"}
          sub="전체 등록 테넌트"
          color="hsl(var(--primary))"
        />
        <StatCard
          label="오늘 총 요청"
          value={usage?.totalRequestsToday?.toLocaleString() ?? "—"}
          sub="플랫폼 전체 합산"
          color="hsl(var(--success))"
        />
        <StatCard
          label="오늘 총 비용"
          value={usage?.totalCostTodayUsd != null ? `$${Number(usage.totalCostTodayUsd).toFixed(2)}` : "—"}
          sub="LLM API 비용 합계"
          color="hsl(var(--info))"
        />
        <StatCard
          label="평균 응답시간"
          value={usage?.avgLatencyMs != null ? `${Math.round(usage.avgLatencyMs)}ms` : "—"}
          sub="전체 테넌트 평균"
          color="hsl(var(--warning))"
        />
      </div>

      {/* Tenant Usage Table */}
      {tenantUsages.length === 0 ? (
        <EmptyState icon={<Globe className="size-6" />} title="테넌트 사용량 데이터가 없습니다" description="테넌트가 생성되고 사용되면 데이터가 표시됩니다" />
      ) : (
        <>
          <div className="mb-6">
            <div className="text-sm font-semibold text-foreground mb-3">
              테넌트별 사용량
            </div>
            <DataTable columns={columns} data={tenantUsages} keyExtractor={(t) => t.tenantId} />
          </div>

          {/* Bar chart */}
          <div className="bg-card border border-border rounded-xl p-5">
            <div className="text-sm font-semibold text-foreground mb-4">
              테넌트별 비용 비교
            </div>
            {tenantUsages.map((t, i) => {
              const pct = ((t.costUsd ?? 0) / maxCost) * 100;
              return (
                <div key={t.tenantId} className="mb-3.5">
                  <div className="flex justify-between mb-1.5">
                    <span className="text-xs text-foreground">
                      {t.tenantName ?? t.tenantId}
                    </span>
                    <span className="text-[11px] font-mono text-muted-foreground">
                      ${(t.costUsd ?? 0).toFixed(4)} · {t.requestCount?.toLocaleString() ?? 0}건
                    </span>
                  </div>
                  <div className="h-2 bg-muted rounded overflow-hidden">
                    <div
                      className="h-full rounded transition-[width] duration-800 ease-out"
                      style={{
                        width: `${pct}%`,
                        background: BAR_COLORS[i % BAR_COLORS.length],
                      }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
    </Page>
  );
}
