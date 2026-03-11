import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Workflow, WorkflowRequest, WorkflowRun } from "../types/workflow";

export const workflowsApi = {
  list: (params?: { page?: number; size?: number }) =>
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
};
