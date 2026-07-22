import { useQuery } from "@tanstack/react-query";
import { workflowsApi } from "../api/workflows";
import type { Pagination } from "../types/api";
import type { WorkflowRun } from "../types/workflow";

/** CR-102: 전체 워크플로우 횡단 실행 내역 (페이지네이션 + 필터). */
export const useAllWorkflowRuns = (params: {
  page?: number;
  size?: number;
  workflow_id?: string;
  status?: string;
}) =>
  useQuery({
    queryKey: ["workflow-runs", params],
    queryFn: () =>
      workflowsApi.allRuns(params).then((r) => ({
        runs: (r.data.data ?? []) as WorkflowRun[],
        pagination: r.data.pagination as Pagination | undefined,
      })),
    retry: false,
  });

/** CR-102: run 단건 (워크플로우 id 없이). */
export const useWorkflowRun = (runId: string | undefined) =>
  useQuery({
    queryKey: ["workflow-run", runId],
    queryFn: () => workflowsApi.getRunById(runId!).then((r) => r.data.data),
    enabled: !!runId,
    retry: false,
  });

/**
 * CR-102: run 이벤트 타임라인 (메타만 — 본문은 행 펼침 시 단건 조회).
 * CR-108: includeBody=true 시 본문 전문을 일괄 조회 — 채팅 흐름 뷰가 모든 블록 본문을 한 번에 필요로 함.
 * @param refetchInterval 진행중 run 폴링용 (ms). 0/undefined 면 폴링 안 함.
 */
export const useWorkflowRunEvents = (
  runId: string | undefined,
  opts?: { includeBody?: boolean; refetchInterval?: number },
) =>
  useQuery({
    queryKey: ["workflow-run-events", runId, opts?.includeBody ?? false],
    queryFn: () =>
      workflowsApi.runEvents(runId!, opts?.includeBody ?? false).then((r) => r.data.data ?? []),
    enabled: !!runId,
    retry: false,
    refetchInterval: opts?.refetchInterval && opts.refetchInterval > 0 ? opts.refetchInterval : false,
  });

/** CR-102: 이벤트 단건 본문 전문 — 행 펼침 시에만 fetch (enabled). */
export const useWorkflowRunEvent = (
  runId: string | undefined,
  eventId: number,
  enabled: boolean
) =>
  useQuery({
    queryKey: ["workflow-run-event", runId, eventId],
    queryFn: () => workflowsApi.runEvent(runId!, eventId).then((r) => r.data.data),
    enabled: enabled && !!runId,
    staleTime: Infinity, // 이벤트는 append-only — 본문 불변
    retry: false,
  });
