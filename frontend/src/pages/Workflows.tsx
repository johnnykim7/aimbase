import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Zap } from "lucide-react";
import { ActionButton } from "../components/common/ActionButton";
import { Badge } from "../components/common/Badge";
import { DataTable, type Column } from "../components/common/DataTable";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Modal } from "../components/common/Modal";
import { Page } from "../components/layout/Page";
import { useWorkflows, useDeleteWorkflow } from "../hooks/useWorkflows";
import type { Workflow } from "../types/workflow";

export default function Workflows() {
  const navigate = useNavigate();
  const [myOnly, setMyOnly] = useState(false);
  const { data: workflows = [], isLoading } = useWorkflows(myOnly || undefined);
  const deleteWorkflow = useDeleteWorkflow();
  const [deleteTarget, setDeleteTarget] = useState<Workflow | null>(null);

  const handleDelete = () => {
    if (!deleteTarget) return;
    deleteWorkflow.mutate(deleteTarget.id, { onSuccess: () => setDeleteTarget(null) });
  };

  const columns: Column<Workflow>[] = [
    {
      header: "이름",
      render: (w) => (
        <span className="font-semibold text-foreground">{w.name}</span>
      ),
    },
    {
      header: "트리거",
      render: (w) => (
        <span className="font-mono text-xs text-muted-foreground">
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
        <span className="font-mono text-xs">{w.runCount ?? 0}회</span>
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
          <span className="text-muted-foreground/40">—</span>
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
        <div className="flex gap-1" onClick={(e) => e.stopPropagation()}>
          <ActionButton small variant="ghost" onClick={() => navigate(`/workflows/${w.id}/edit`)}>
            편집
          </ActionButton>
          <ActionButton small variant="danger" onClick={() => setDeleteTarget(w)}>
            삭제
          </ActionButton>
        </div>
      ),
      width: "120px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={
        <div className="flex gap-2 items-center">
          <ActionButton
            variant={myOnly ? "primary" : "ghost"}
            small
            onClick={() => setMyOnly((v) => !v)}
          >
            {myOnly ? "내 워크플로우" : "전체"}
          </ActionButton>
          <ActionButton variant="primary" small onClick={() => navigate("/workflows/new")}>
            + 새 워크플로우
          </ActionButton>
        </div>
      }
    >
      {workflows.length === 0 ? (
        <EmptyState
          icon={<Zap className="size-6" />}
          title="등록된 워크플로우가 없습니다"
          description="LLM 호출, Tool 사용, 조건 분기, 병렬 실행을 조합한 DAG를 구성하세요"
        />
      ) : (
        <DataTable
          columns={columns}
          data={workflows}
          keyExtractor={(w) => w.id}
          onRowClick={(w) => navigate(`/workflows/${w.id}`)}
        />
      )}

      <Modal open={!!deleteTarget} onClose={() => setDeleteTarget(null)} title="워크플로우 삭제" width={420}>
        <p className="text-[13px] text-muted-foreground mb-2 leading-relaxed">
          <span className="font-semibold text-foreground">{deleteTarget?.name}</span>을(를) 삭제하시겠습니까?
        </p>
        <p className="text-xs text-destructive mb-6">
          관련된 실행 이력도 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.
        </p>
        <div className="flex justify-end gap-2">
          <ActionButton variant="ghost" onClick={() => setDeleteTarget(null)}>
            취소
          </ActionButton>
          <ActionButton variant="danger" onClick={handleDelete} disabled={deleteWorkflow.isPending}>
            {deleteWorkflow.isPending ? "삭제 중..." : "삭제"}
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
