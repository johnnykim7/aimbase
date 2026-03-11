import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Connection, ConnectionRequest, TestResult } from "../types/connection";

export const connectionsApi = {
  list: (params?: { page?: number; size?: number; type?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<Connection> | Connection[]>>("/connections", { params }),

  get: (id: string) =>
    apiClient.get<ApiResponse<Connection>>(`/connections/${id}`),

  create: (data: ConnectionRequest) =>
    apiClient.post<ApiResponse<Connection>>("/connections", data),

  update: (id: string, data: Partial<ConnectionRequest>) =>
    apiClient.put<ApiResponse<Connection>>(`/connections/${id}`, data),

  delete: (id: string) =>
    apiClient.delete(`/connections/${id}`),

  test: (id: string) =>
    apiClient.post<ApiResponse<TestResult>>(`/connections/${id}/test`),
};
