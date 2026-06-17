import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Workflow, WorkflowRequest, WorkflowRun, WorkflowRunEvent } from "../types/workflow";

export const workflowsApi = {
  list: (params?: { page?: number; size?: number; my?: boolean }) =>
    apiClient.get<ApiResponse<PagedResponse<Workflow> | Workflow[]>>("/workflows", { params }),

  get: (id: string) =>
    apiClient.get<ApiResponse<Workflow>>(`/workflows/${id}`),

  create: (data: WorkflowRequest) =>
    apiClient.post<ApiResponse<Workflow>>("/workflows", data),

  update: (id: string, data: Partial<WorkflowRequest>) =>
    apiClient.put<ApiResponse<Workflow>>(`/workflows/${id}`, data),

  delete: (id: string) =>
    apiClient.delete(`/workflows/${id}`),

  run: (id: string, input?: Record<string, unknown>) =>
    apiClient.post<ApiResponse<WorkflowRun>>(`/workflows/${id}/run`, input ?? {}),

  runs: (id: string, params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<WorkflowRun> | WorkflowRun[]>>(`/workflows/${id}/runs`, { params }),

  getRun: (id: string, runId: string) =>
    apiClient.get<ApiResponse<WorkflowRun>>(`/workflows/${id}/runs/${runId}`),

  approveRun: (runId: string) =>
    apiClient.post(`/workflows/runs/${runId}/approve`),

  // CR-116: run 중지. force=true 면 진행 중 CLI worker 를 즉시 kill 하고 즉시 cancelled 로 종료
  // (hang 한 worker 로 협조적 cancel 이 안 먹는 상황 대비).
  cancelRun: (runId: string, force = false) =>
    apiClient.post<ApiResponse<WorkflowRun>>(
      `/workflows/runs/${runId}/cancel`,
      undefined,
      force ? { params: { force: true } } : undefined,
    ),

  // CR-116: run 재실행 — 원 run 의 입력으로 같은 워크플로우를 새 run 으로 다시 실행.
  rerunRun: (runId: string) =>
    apiClient.post<ApiResponse<WorkflowRun>>(`/workflows/runs/${runId}/rerun`),

  // CR-102: 전체 워크플로우 횡단 실행 내역
  allRuns: (params?: { page?: number; size?: number; workflow_id?: string; status?: string }) =>
    apiClient.get<ApiResponse<WorkflowRun[]>>("/workflows/runs", { params }),

  getRunById: (runId: string) =>
    apiClient.get<ApiResponse<WorkflowRun>>(`/workflows/runs/${runId}`),

  // CR-102: run 이벤트 타임라인 (메타) / 단건 본문 전문
  // CR-108: includeBody=true 시 본문 전문(prompt/response/input/output)을 일괄 반환 — 채팅 흐름 뷰용.
  runEvents: (runId: string, includeBody = false) =>
    apiClient.get<ApiResponse<WorkflowRunEvent[]>>(
      `/workflows/runs/${runId}/events`,
      includeBody ? { params: { include_body: true } } : undefined,
    ),

  runEvent: (runId: string, eventId: number) =>
    apiClient.get<ApiResponse<WorkflowRunEvent>>(`/workflows/runs/${runId}/events/${eventId}`),
};
