import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronLeft, ChevronRight, History } from "lucide-react";
import { Badge, type BadgeColor } from "../components/common/Badge";
import { DataTable, type Column } from "../components/common/DataTable";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { inputStyle } from "../components/common/FormField";
import { useWorkflows } from "../hooks/useWorkflows";
import { useAllWorkflowRuns } from "../hooks/useWorkflowRuns";
import type { WorkflowRun } from "../types/workflow";

export const RUN_STATUS_COLORS: Record<string, BadgeColor> = {
  running: "accent",
  completed: "success",
  failed: "danger",
  pending_approval: "warning",
  cancelled: "muted",
};

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "전체 상태" },
  { value: "running", label: "Running" },
  { value: "completed", label: "Completed" },
  { value: "failed", label: "Failed" },
  { value: "pending_approval", label: "Pending Approval" },
  { value: "cancelled", label: "Cancelled" },
];

export function formatDuration(startedAt?: string, completedAt?: string): string {
  if (!startedAt || !completedAt) return "--";
  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime();
  if (ms < 0) return "--";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}

export default function WorkflowRuns() {
  const navigate = useNavigate();
  const [workflowFilter, setWorkflowFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(0);

  const { data: workflows = [] } = useWorkflows();
  const workflowNames = useMemo(
    () => new Map(workflows.map((w) => [w.id, w.name])),
    [workflows]
  );

  const { data, isLoading } = useAllWorkflowRuns({
    page,
    size: 20,
    workflow_id: workflowFilter || undefined,
    status: statusFilter || undefined,
  });
  const runs = data?.runs ?? [];
  const pagination = data?.pagination;

  const columns: Column<WorkflowRun>[] = [
    {
      header: "워크플로우",
      render: (r) => (
        <span className="text-[13px] font-medium text-foreground">
          {workflowNames.get(r.workflowId) ?? r.workflowId}
        </span>
      ),
    },
    {
      header: "상태",
      render: (r) => (
        <Badge color={RUN_STATUS_COLORS[r.status] ?? "muted"} pulse={r.status === "running"}>
          {r.status}
        </Badge>
      ),
      width: "140px",
    },
    {
      header: "현재 스텝",
      render: (r) =>
        r.currentStep ? (
          <span className="text-xs font-mono text-muted-foreground">{r.currentStep}</span>
        ) : (
          <span className="text-muted-foreground/60">--</span>
        ),
      width: "150px",
    },
    {
      header: "시작",
      render: (r) => (
        <span className="text-xs text-muted-foreground">
          {r.startedAt ? new Date(r.startedAt).toLocaleString("ko-KR") : "--"}
        </span>
      ),
      width: "170px",
    },
    {
      header: "소요",
      render: (r) => (
        <span className="text-xs font-mono text-muted-foreground">
          {formatDuration(r.startedAt, r.completedAt)}
        </span>
      ),
      width: "90px",
    },
    {
      header: "Run ID",
      render: (r) => (
        <span className="text-[11px] font-mono text-muted-foreground/60">
          {r.id.slice(0, 8)}...
        </span>
      ),
      width: "110px",
    },
  ];

  return (
    <Page>
      {/* Filters */}
      <div className="flex gap-3 mb-5">
        <select
          style={{ ...inputStyle, width: 220 }}
          value={workflowFilter}
          onChange={(e) => { setWorkflowFilter(e.target.value); setPage(0); }}
        >
          <option value="">전체 워크플로우</option>
          {workflows.map((w) => (
            <option key={w.id} value={w.id}>{w.name}</option>
          ))}
        </select>
        <select
          style={{ ...inputStyle, width: 180 }}
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }}
        >
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>

      {runs.length === 0 && !isLoading ? (
        <EmptyState
          icon={<History className="size-6" />}
          title="실행 내역이 없습니다"
          description="워크플로우가 실행되면 여기에 표시됩니다"
        />
      ) : (
        <>
          <DataTable
            columns={columns}
            data={runs}
            keyExtractor={(r) => r.id}
            loading={isLoading}
            emptyMessage="실행 내역이 없습니다"
            onRowClick={(r) => navigate(`/workflow-runs/${r.id}`)}
          />
          {pagination && pagination.totalPages > 1 && (
            <div className="flex items-center justify-end gap-2 mt-4">
              <span className="text-xs text-muted-foreground mr-2">
                {page + 1} / {pagination.totalPages} 페이지 (총 {pagination.totalElements}건)
              </span>
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
                className="p-1.5 rounded-md border border-border text-muted-foreground hover:text-foreground disabled:opacity-40 disabled:cursor-default cursor-pointer bg-card"
              >
                <ChevronLeft className="size-4" />
              </button>
              <button
                disabled={page + 1 >= pagination.totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="p-1.5 rounded-md border border-border text-muted-foreground hover:text-foreground disabled:opacity-40 disabled:cursor-default cursor-pointer bg-card"
              >
                <ChevronRight className="size-4" />
              </button>
            </div>
          )}
        </>
      )}
    </Page>
  );
}
