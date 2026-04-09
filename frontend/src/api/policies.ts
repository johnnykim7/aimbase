import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Policy, PolicyRequest, SimulateRequest, SimulateResult } from "../types/policy";

export const policiesApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<Policy> | Policy[]>>("/policies", { params }),

  get: (id: string) =>
    apiClient.get<ApiResponse<Policy>>(`/policies/${id}`),

  create: (data: PolicyRequest) =>
    apiClient.post<ApiResponse<Policy>>("/policies", data),

  update: (id: string, data: Partial<PolicyRequest>) =>
    apiClient.put<ApiResponse<Policy>>(`/policies/${id}`, data),

  delete: (id: string) =>
    apiClient.delete(`/policies/${id}`),

  simulate: (_id: string, data: SimulateRequest) =>
    apiClient.post<ApiResponse<SimulateResult>>("/policies/simulate", data),
};
