import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface PromptTemplate {
  key: string;
  version: number;
  /** CR-049: GLOBAL / TENANT / PROJECT */
  scope?: "GLOBAL" | "TENANT" | "PROJECT";
  /** CR-049: scope=PROJECT 일 때만 세팅 */
  project_id?: string | null;
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

/** CR-049 PRD-305: cascade 병합 미리보기 응답 */
export interface PromptCascadePreview {
  key: string;
  project_id: string | null;
  global: string | null;
  tenant: string | null;
  project: string | null;
  merged: string | null;
  total_length_bytes: number;
  warning: string | null;
}

export const promptTemplatesApi = {
  list: (params?: { category?: string; scope?: "GLOBAL" | "TENANT" | "PROJECT"; projectId?: string }) =>
    apiClient.get<ApiResponse<PromptTemplate[]>>("/prompt-templates", { params }),

  preview: (key: string, projectId?: string) =>
    apiClient.get<ApiResponse<PromptCascadePreview>>("/prompt-templates/preview", {
      params: { key, projectId },
    }),

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
