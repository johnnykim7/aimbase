import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type {
  ConnectionGroup,
  ConnectionGroupRequest,
  GroupTestResult,
} from "../types/connectionGroup";

export const connectionGroupsApi = {
  list: (params?: { adapter?: string }) =>
    apiClient.get<ApiResponse<ConnectionGroup[]>>("/connection-groups", { params }),

  get: (id: string) =>
    apiClient.get<ApiResponse<ConnectionGroup>>(`/connection-groups/${id}`),

  create: (data: ConnectionGroupRequest) =>
    apiClient.post<ApiResponse<ConnectionGroup>>("/connection-groups", data),

  update: (id: string, data: ConnectionGroupRequest) =>
    apiClient.put<ApiResponse<ConnectionGroup>>(`/connection-groups/${id}`, data),

  delete: (id: string) =>
    apiClient.delete(`/connection-groups/${id}`),

  test: (id: string) =>
    apiClient.post<ApiResponse<GroupTestResult[]>>(`/connection-groups/${id}/test`),
};
