import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface PromptTemplate {
  key: string;
  version: number;
  category: string;
  name: string;
  description: string;
  template: string;
  variables: { name: string; type: string; required: boolean; default?: string; description?: string }[];
  language: string;
  is_active: boolean;
  is_system: boolean;
  created_by: string;
  created_at: string;
  updated_at: string;
}

export const promptTemplatesApi = {
  list: (category?: string) =>
    apiClient.get<ApiResponse<PromptTemplate[]>>("/prompt-templates", { params: { category } }),

  get: (key: string, version: number) =>
    apiClient.get<ApiResponse<PromptTemplate>>(`/prompt-templates/${key}/${version}`),

  versions: (key: string) =>
    apiClient.get<ApiResponse<PromptTemplate[]>>(`/prompt-templates/${key}/versions`),

  create: (body: Partial<PromptTemplate>) =>
    apiClient.post<ApiResponse<PromptTemplate>>("/prompt-templates", body),

  update: (key: string, version: number, body: Partial<PromptTemplate>) =>
    apiClient.put<ApiResponse<PromptTemplate>>(`/prompt-templates/${key}/${version}`, body),

  delete: (key: string, version: number) =>
    apiClient.delete<ApiResponse<{ deleted: string; version: number }>>(`/prompt-templates/${key}/${version}`),

  render: (key: string, version: number, variables: Record<string, string>) =>
    apiClient.post<ApiResponse<{ rendered: string; token_estimate: number }>>(`/prompt-templates/${key}/${version}/render`, variables),

  bulk: (category?: string) =>
    apiClient.get<ApiResponse<Record<string, string>>>("/prompt-templates/bulk", { params: { category } }),

  clearCache: () =>
    apiClient.delete<ApiResponse<{ status: string }>>("/prompt-templates/cache"),
};
