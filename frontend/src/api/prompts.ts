import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Prompt, PromptRequest, PromptTestRequest, PromptTestResult } from "../types/prompt";

export const promptsApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<Prompt> | Prompt[]>>("/prompts", { params }),

  get: (id: string, version?: string) =>
    apiClient.get<ApiResponse<Prompt>>(`/prompts/${id}${version ? `/${version}` : ""}`),

  create: (data: PromptRequest) =>
    apiClient.post<ApiResponse<Prompt>>("/prompts", data),

  update: (id: string, version: string, data: Partial<PromptRequest>) =>
    apiClient.put<ApiResponse<Prompt>>(`/prompts/${id}/${version}`, data),

  delete: (id: string, version?: string) =>
    apiClient.delete(`/prompts/${id}${version ? `/${version}` : ""}`),

  test: (id: string, version: string, data: PromptTestRequest) =>
    apiClient.post<ApiResponse<PromptTestResult>>(`/prompts/${id}/${version}/test`, data),
};
