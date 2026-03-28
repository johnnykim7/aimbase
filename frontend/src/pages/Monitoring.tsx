import { useMemo } from "react";
import {
  BarChart, Bar, LineChart, Line, XAxis, YAxis,
  CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from "recharts";
import { Badge } from "../components/common/Badge";
import { StatCard } from "../components/common/StatCard";
import { DataTable, type Column } from "../components/common/DataTable";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { useUsage, useCostBreakdown, useCostTrend } from "../hooks/useAdmin";
import { useModels } from "../hooks/useMonitoring";
import type { UsageStat } from "../types/admin";
import type { CostBreakdown, CostTrendPoint } from "../types/admin";

/* Recharts는 Tailwind 클래스를 지원하지 않으므로 raw color 상수 사용 */
const CHART_COLORS = {
  primary: "#2563eb",
  purple: "#7c3aed",
  warning: "#d97706",
  success: "#059669",
  danger: "#dc2626",
  border: "#e5e7eb",
  textMuted: "#6b7280",
  surface: "#ffffff",
};

const BAR_COLORS = [CHART_COLORS.primary, CHART_COLORS.purple, CHART_COLORS.warning, CHART_COLORS.success, CHART_COLORS.danger];

export default function Monitoring() {
  const { data: usageRaw, isLoading: usageLoading } = useUsage();
  const { data: models = [] } = useModels();
  const { data: costBreakdownRaw } = useCostBreakdown();
  const { data: costTrendRaw } = useCostTrend();

  const costBreakdown: CostBreakdown[] = Array.isArray(costBreakdownRaw) ? costBreakdownRaw : [];

  const costTrendData = useMemo(() => {
    const raw: CostTrendPoint[] = Array.isArray(costTrendRaw) ? costTrendRaw : [];
    if (raw.length === 0) return { chartData: [] as Record<string, unknown>[], modelNames: [] as string[] };

    const modelSet = new Set<string>();
    const dateMap: Record<string, Record<string, number>> = {};
    for (const pt of raw) {
      modelSet.add(pt.model);
      if (!dateMap[pt.date]) dateMap[pt.date] = {};
      dateMap[pt.date][pt.model] = pt.cost;
    }
    const modelNames = Array.from(modelSet);
    const chartData = Object.entries(dateMap)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([date, models]) => ({ date, ...models }));
    return { chartData, modelNames };
  }, [costTrendRaw]);

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
        <span className="font-mono text-primary text-[13px]">
          {u.modelName ?? u.modelId ?? "—"}
        </span>
      ),
    },
    {
      header: "요청 수",
      render: (u) => (
        <span className="font-mono">{(u.requestCount ?? 0).toLocaleString()}</span>
      ),
      width: "100px",
    },
    {
      header: "토큰",
      render: (u) => (
        <span className="font-mono">{(u.tokenCount ?? 0).toLocaleString()}</span>
      ),
      width: "110px",
    },
    {
      header: "비용",
      render: (u) => (
        <span className="font-mono text-info">
          ${(u.costUsd ?? 0).toFixed(4)}
        </span>
      ),
      width: "100px",
    },
    {
      header: "평균 응답시간",
      render: (u) => (
        <span className="font-mono">{u.avgLatencyMs != null ? `${u.avgLatencyMs}ms` : "—"}</span>
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
          <span className="text-muted-foreground/40">—</span>
        ),
      width: "90px",
    },
  ];

  if (usageLoading) return <LoadingSpinner fullPage />;

  return (
    <Page>

      {/* Stat Cards */}
      <div className="flex gap-4 mb-7 flex-wrap">
        <StatCard label="총 비용 (오늘)" value={`$${totalCost.toFixed(2)}`} sub="LLM API 총액" color="hsl(var(--info))" />
        <StatCard label="총 토큰" value={totalTokens.toLocaleString()} sub="Input + Output 합계" color="hsl(var(--primary))" />
        <StatCard label="총 요청" value={totalRequests.toLocaleString()} sub="모든 모델 합산" color="hsl(var(--success))" />
        <StatCard label="평균 응답시간" value={avgLatency > 0 ? `${avgLatency.toFixed(0)}ms` : "—"} sub="전체 평균" color="hsl(var(--warning))" />
      </div>

      {/* Model Performance Table */}
      <div className="mb-6">
        <div className="text-sm font-semibold text-foreground mb-3">
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
        <div className="bg-card border border-border rounded-xl p-5">
          <div className="text-sm font-semibold text-foreground mb-4">
            모델별 비용 비교
          </div>
          {usageList.map((u, i) => {
            const pct = totalCost > 0 ? ((u.costUsd ?? 0) / totalCost) * 100 : 0;
            return (
              <div key={u.modelId ?? i} className="mb-3.5">
                <div className="flex justify-between mb-1.5">
                  <span className="text-xs text-foreground">
                    {u.modelName ?? u.modelId ?? `Model ${i + 1}`}
                  </span>
                  <span className="text-[11px] font-mono text-muted-foreground">
                    ${(u.costUsd ?? 0).toFixed(4)} · {pct.toFixed(1)}%
                  </span>
                </div>
                <div className="h-1.5 bg-muted rounded-sm overflow-hidden">
                  <div
                    className="h-full rounded-sm transition-[width] duration-1000 ease-out"
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
      )}

      {/* Available Models */}
      {models.length > 0 && (
        <div className="mt-6">
          <div className="text-sm font-semibold text-foreground mb-3">
            사용 가능한 모델
          </div>
          <div className="flex flex-wrap gap-2">
            {models.map((m) => (
              <div
                key={m.id}
                className="py-2 px-3.5 rounded-lg bg-card border border-border text-xs font-mono text-foreground"
              >
                {m.name ?? m.id}
                {m.provider && (
                  <span className="text-muted-foreground/40 ml-1.5">({m.provider})</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 모델별 비용 분석 (BarChart) */}
      {costBreakdown.length > 0 && (
        <div className="mt-7 bg-card border border-border rounded-xl p-5">
          <div className="text-sm font-semibold text-foreground mb-4">
            모델별 비용 분석
          </div>
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={costBreakdown} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.border} />
              <XAxis
                dataKey="model"
                tick={{ fontSize: 11, fontFamily: "monospace", fill: CHART_COLORS.textMuted }}
              />
              <YAxis
                tick={{ fontSize: 11, fontFamily: "monospace", fill: CHART_COLORS.textMuted }}
                tickFormatter={(v: number) => `$${v.toFixed(2)}`}
              />
              <Tooltip
                contentStyle={{
                  background: CHART_COLORS.surface,
                  border: `1px solid ${CHART_COLORS.border}`,
                  borderRadius: 8,
                  fontSize: 12,
                }}
                formatter={(value: unknown, name: unknown) => [
                  `$${Number(value).toFixed(4)}`,
                  String(name) === "inputCost" ? "입력 비용" : "출력 비용",
                ]}
              />
              <Legend
                formatter={(value: string) =>
                  value === "inputCost" ? "입력 비용" : "출력 비용"
                }
              />
              <Bar dataKey="inputCost" stackId="cost" fill={CHART_COLORS.primary} radius={[0, 0, 0, 0]} />
              <Bar dataKey="outputCost" stackId="cost" fill={CHART_COLORS.purple} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* 일별 비용 추세 (LineChart) */}
      {costTrendData.chartData.length > 0 && (
        <div className="mt-5 bg-card border border-border rounded-xl p-5">
          <div className="text-sm font-semibold text-foreground mb-4">
            일별 비용 추세
          </div>
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={costTrendData.chartData} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.border} />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 11, fontFamily: "monospace", fill: CHART_COLORS.textMuted }}
              />
              <YAxis
                tick={{ fontSize: 11, fontFamily: "monospace", fill: CHART_COLORS.textMuted }}
                tickFormatter={(v: number) => `$${v.toFixed(2)}`}
              />
              <Tooltip
                contentStyle={{
                  background: CHART_COLORS.surface,
                  border: `1px solid ${CHART_COLORS.border}`,
                  borderRadius: 8,
                  fontSize: 12,
                }}
                formatter={(value: unknown) => [`$${Number(value).toFixed(4)}`, ""]}
              />
              <Legend />
              {costTrendData.modelNames.map((name, i) => (
                <Line
                  key={name}
                  type="monotone"
                  dataKey={name}
                  stroke={BAR_COLORS[i % BAR_COLORS.length]}
                  strokeWidth={2}
                  dot={{ r: 3 }}
                  activeDot={{ r: 5 }}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </Page>
  );
}
