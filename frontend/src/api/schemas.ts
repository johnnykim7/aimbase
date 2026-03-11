import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Schema, SchemaRequest, ValidateResult } from "../types/schema";

export const schemasApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<Schema> | Schema[]>>("/schemas", { params }),

  get: (id: string, version?: string) =>
    apiClient.get<ApiResponse<Schema>>(`/schemas/${id}${version ? `/${version}` : ""}`),

  create: (data: SchemaRequest) =>
    apiClient.post<ApiResponse<Schema>>("/schemas", data),

  update: (id: string, version: string, data: Partial<SchemaRequest>) =>
    apiClient.put<ApiResponse<Schema>>(`/schemas/${id}/${version}`, data),

  delete: (id: string, version?: string) =>
    apiClient.delete(`/schemas/${id}${version ? `/${version}` : ""}`),

  validate: (id: string, version: string, data: Record<string, unknown>) =>
    apiClient.post<ApiResponse<ValidateResult>>(`/schemas/${id}/${version}/validate`, data),
};
