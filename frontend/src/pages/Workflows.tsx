import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { DataTable, type Column } from "../components/common/DataTable";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
import { useWorkflows, useDeleteWorkflow, useRunWorkflow } from "../hooks/useWorkflows";
import type { Workflow, WorkflowStep } from "../types/workflow";

const STEP_TYPE_COLORS: Record<string, string> = {
  llm: COLORS.accent,
  tool: COLORS.purple,
  condition: COLORS.warning,
  parallel: COLORS.success,
  approval: COLORS.danger,
  action: COLORS.accentDim,
};

const STEP_TYPE_ICONS: Record<string, string> = {
  llm: "🤖",
  tool: "🔧",
  condition: "⑂",
  parallel: "⚡",
  approval: "✋",
  action: "▶",
};

function FlowPreview({ steps }: { steps: WorkflowStep[] }) {
  return (
    <div
      style={{
        background: `radial-gradient(circle, ${COLORS.border}30 1px, transparent 1px)`,
        backgroundSize: "20px 20px",
        borderRadius: 8,
        padding: 20,
        minHeight: 160,
        position: "relative",
      }}
    >
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 0 }}>
        {steps.map((step, i) => {
          const color = STEP_TYPE_COLORS[step.type] ?? COLORS.textDim;
          return (
            <div key={step.id} style={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
              <div
                style={{
                  padding: "10px 20px",
                  borderRadius: 10,
                  border: `1px solid ${color}`,
                  background: color + "15",
                  fontSize: 12,
                  fontFamily: FONTS.sans,
                  fontWeight: 600,
                  color: COLORS.text,
                  display: "flex",
                  alignItems: "center",
                  gap: 6,
                  minWidth: 160,
                  justifyContent: "center",
                }}
              >
                <span>{STEP_TYPE_ICONS[step.type] ?? "▶"}</span>
                {step.name}
              </div>
              {i < steps.length - 1 && (
                <div style={{ width: 2, height: 20, background: COLORS.border, position: "relative" }}>
                  <div style={{ position: "absolute", bottom: -4, left: -3, width: 8, height: 8, borderRight: `2px solid ${COLORS.textDim}`, borderBottom: `2px solid ${COLORS.textDim}`, transform: "rotate(45deg)" }} />
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default function Workflows() {
  const navigate = useNavigate();
  const { data: workflows = [], isLoading } = useWorkflows();
  const deleteWorkflow = useDeleteWorkflow();
  const runWorkflow = useRunWorkflow();

  const [selectedWorkflow, setSelectedWorkflow] = useState<Workflow | null>(null);
  const [runResult, setRunResult] = useState<Record<string, string>>({});

  const handleRun = async (id: string) => {
    const result = await runWorkflow.mutateAsync({ id });
    setRunResult((p) => ({ ...p, [id]: result?.status ?? "started" }));
  };

  const columns: Column<Workflow>[] = [
    {
      header: "이름",
      render: (w) => (
        <span style={{ fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>{w.name}</span>
      ),
    },
    {
      header: "트리거",
      render: (w) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 12, color: COLORS.textMuted }}>
          {w.trigger ?? "—"}
        </span>
      ),
    },
    {
      header: "단계",
      render: (w) => <Badge color="muted">{w.stepCount ?? w.steps?.length ?? 0}단계</Badge>,
      width: "80px",
    },
    {
      header: "실행 횟수",
      render: (w) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 12 }}>{w.runCount ?? 0}회</span>
      ),
      width: "90px",
    },
    {
      header: "성공률",
      render: (w) =>
        w.successRate != null ? (
          <Badge color={w.successRate >= 90 ? "success" : w.successRate >= 70 ? "warning" : "danger"}>
            {w.successRate.toFixed(0)}%
          </Badge>
        ) : (
          <span style={{ color: COLORS.textDim }}>—</span>
        ),
      width: "80px",
    },
    {
      header: "상태",
      render: (w) => (
        <Badge color={w.status === "active" ? "success" : w.status === "inactive" ? "muted" : "warning"}>
          {w.status ?? "draft"}
        </Badge>
      ),
      width: "90px",
    },
    {
      header: "액션",
      render: (w) => (
        <div style={{ display: "flex", gap: 4 }}>
          <ActionButton small variant="ghost" onClick={() => navigate(`/workflows/${w.id}/edit`)}>
            편집
          </ActionButton>
          <ActionButton small variant="default" disabled={runWorkflow.isPending} onClick={() => handleRun(w.id)}>
            {runResult[w.id] ? runResult[w.id] : "실행"}
          </ActionButton>
          <ActionButton small variant="danger" onClick={() => deleteWorkflow.mutate(w.id)}>
            삭제
          </ActionButton>
        </div>
      ),
      width: "180px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title="워크플로우"
        subtitle="DAG 기반 다단계 AI 오케스트레이션"
        actions={
          <ActionButton variant="primary" small onClick={() => navigate("/workflows/new")}>
            + 새 워크플로우
          </ActionButton>
        }
      />

      {workflows.length === 0 ? (
        <EmptyState
          icon="⚡"
          title="등록된 워크플로우가 없습니다"
          description="LLM 호출, Tool 사용, 조건 분기, 병렬 실행을 조합한 DAG를 구성하세요"
        />
      ) : (
        <>
          <DataTable
            columns={columns}
            data={workflows}
            keyExtractor={(w) => w.id}
            onRowClick={(w) => setSelectedWorkflow(w)}
          />

          {/* Visual Flow Preview */}
          {selectedWorkflow && (
            <div
              style={{
                marginTop: 20,
                background: COLORS.surface,
                border: `1px solid ${COLORS.border}`,
                borderRadius: 12,
                overflow: "hidden",
              }}
            >
              <div
                style={{
                  padding: "14px 20px",
                  borderBottom: `1px solid ${COLORS.border}`,
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                }}
              >
                <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                  <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
                    {selectedWorkflow.name}
                  </span>
                  <Badge color={selectedWorkflow.status === "active" ? "success" : "muted"}>
                    {selectedWorkflow.status ?? "draft"}
                  </Badge>
                </div>
                <div style={{ display: "flex", gap: 6 }}>
                  <ActionButton
                    variant="primary"
                    small
                    disabled={runWorkflow.isPending}
                    onClick={() => handleRun(selectedWorkflow.id)}
                  >
                    테스트 실행
                  </ActionButton>
                  <ActionButton variant="ghost" small onClick={() => setSelectedWorkflow(null)}>
                    닫기
                  </ActionButton>
                </div>
              </div>
              <div style={{ padding: 20 }}>
                {(selectedWorkflow.steps ?? []).length > 0 ? (
                  <FlowPreview steps={selectedWorkflow.steps!} />
                ) : (
                  <div style={{ textAlign: "center", padding: 32, color: COLORS.textDim, fontFamily: FONTS.mono, fontSize: 12 }}>
                    워크플로우 단계 정보 없음
                  </div>
                )}

                {/* Node palette */}
                <div style={{ marginTop: 16, padding: "12px 0", borderTop: `1px solid ${COLORS.border}` }}>
                  <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 8 }}>
                    노드 팔레트
                  </div>
                  <div style={{ display: "flex", gap: 8 }}>
                    {Object.entries(STEP_TYPE_ICONS).map(([type, icon]) => (
                      <div
                        key={type}
                        style={{
                          padding: "6px 12px",
                          borderRadius: 8,
                          border: `1px solid ${STEP_TYPE_COLORS[type] ?? COLORS.border}`,
                          background: (STEP_TYPE_COLORS[type] ?? COLORS.border) + "15",
                          fontSize: 11,
                          fontFamily: FONTS.mono,
                          color: STEP_TYPE_COLORS[type] ?? COLORS.textMuted,
                          display: "flex",
                          alignItems: "center",
                          gap: 4,
                        }}
                      >
                        {icon} {type}
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
