import { useState } from "react";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { Clock, Trash2, ToggleLeft, ToggleRight } from "lucide-react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { scheduledJobsApi, type ScheduledJob } from "../api/scheduledJobs";

export default function ScheduledJobs() {
  const queryClient = useQueryClient();
  const { data: jobs = [], isLoading } = useQuery({
    queryKey: ["scheduled-jobs"],
    queryFn: () => scheduledJobsApi.list().then((r) => r.data.data ?? []),
  });

  const createJob = useMutation({
    mutationFn: scheduledJobsApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["scheduled-jobs"] });
      setShowModal(false);
    },
  });

  const deleteJob = useMutation({
    mutationFn: scheduledJobsApi.delete,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["scheduled-jobs"] }),
  });

  const toggleJob = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      scheduledJobsApi.toggle(id, active),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["scheduled-jobs"] }),
  });

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState({
    name: "",
    cron_expression: "0 0 9 * * *",
    target_type: "WORKFLOW",
    target_id: "",
  });

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={
        <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
          새 스케줄
        </ActionButton>
      }
    >
      {jobs.length === 0 ? (
        <EmptyState
          icon={<Clock className="size-6" />}
          title="등록된 스케줄이 없습니다"
          description="워크플로우나 도구를 Cron 표현식으로 자동 실행하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              스케줄 추가
            </ActionButton>
          }
        />
      ) : (
        <div className="bg-card border border-border rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/50 text-muted-foreground">
                <th className="px-4 py-3 text-left font-medium">이름</th>
                <th className="px-4 py-3 text-left font-medium">Cron</th>
                <th className="px-4 py-3 text-left font-medium">대상</th>
                <th className="px-4 py-3 text-left font-medium">상태</th>
                <th className="px-4 py-3 text-left font-medium">마지막 실행</th>
                <th className="px-4 py-3 text-right font-medium">액션</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job: ScheduledJob) => (
                <tr key={job.id} className="border-b border-border hover:bg-muted/30">
                  <td className="px-4 py-3 font-medium">{job.name}</td>
                  <td className="px-4 py-3 font-mono text-xs">{job.cron_expression}</td>
                  <td className="px-4 py-3">
                    <Badge color="muted">{job.target_type}</Badge>
                    <span className="ml-1 text-muted-foreground">{job.target_id}</span>
                  </td>
                  <td className="px-4 py-3">
                    <Badge color={job.is_active ? "success" : "muted"}>
                      {job.is_active ? "활성" : "비활성"}
                    </Badge>
                    {job.failure_count > 0 && (
                      <Badge color="danger">
                        실패 {job.failure_count}회
                      </Badge>
                    )}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">
                    {job.last_run_status !== "NEVER" ? (
                      <>
                        <Badge
                          color={job.last_run_status === "SUCCESS" ? "success" : "danger"}
                        >
                          {job.last_run_status}
                        </Badge>
                        {job.last_run_at}
                      </>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="px-4 py-3 text-right space-x-1">
                    <button
                      className="p-1.5 rounded hover:bg-muted"
                      title={job.is_active ? "비활성화" : "활성화"}
                      onClick={() => toggleJob.mutate({ id: job.id, active: !job.is_active })}
                    >
                      {job.is_active ? (
                        <ToggleRight className="size-4 text-success" />
                      ) : (
                        <ToggleLeft className="size-4 text-muted-foreground" />
                      )}
                    </button>
                    <button
                      className="p-1.5 rounded hover:bg-destructive/10"
                      title="삭제"
                      onClick={() => {
                        if (confirm(`'${job.name}' 스케줄을 삭제하시겠습니까?`))
                          deleteJob.mutate(job.id);
                      }}
                    >
                      <Trash2 className="size-4 text-destructive" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* 생성 모달 */}
      <Modal
        open={showModal}
        onClose={() => setShowModal(false)}
        title="새 스케줄 추가"
      >
        <div className="space-y-4">
          <FormField label="이름">
            <input
              style={inputStyle}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="일일 보고서 생성"
            />
          </FormField>
          <FormField label="Cron 표현식" hint="초 분 시 일 월 요일 (예: 0 0 9 * * * = 매일 09시)">
            <input
              style={inputStyle}
              value={form.cron_expression}
              onChange={(e) => setForm({ ...form, cron_expression: e.target.value })}
            />
          </FormField>
          <FormField label="대상 타입">
            <select
              style={inputStyle}
              value={form.target_type}
              onChange={(e) => setForm({ ...form, target_type: e.target.value })}
            >
              <option value="WORKFLOW">워크플로우</option>
              <option value="TOOL">도구</option>
            </select>
          </FormField>
          <FormField label="대상 ID">
            <input
              style={inputStyle}
              value={form.target_id}
              onChange={(e) => setForm({ ...form, target_id: e.target.value })}
              placeholder="워크플로우 ID 또는 도구 이름"
            />
          </FormField>
          <ActionButton
            variant="primary"
            onClick={() => createJob.mutate(form)}
            disabled={createJob.isPending}
          >
            생성
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
