import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type { PromptTemplate } from "./promptTemplates";

/** CR-049 PRD-305: 프로젝트 단위 시스템 지침 편의 API */
export const projectInstructionsApi = {
  list: (projectId: string) =>
    apiClient.get<ApiResponse<{ project_id: string; templates: PromptTemplate[] }>>(
      `/projects/${projectId}/instructions`,
    ),

  upsert: (
    projectId: string,
    body: { key: string; template: string; category?: string; name?: string; language?: string; is_active?: boolean },
  ) =>
    apiClient.put<ApiResponse<PromptTemplate>>(`/projects/${projectId}/instructions`, body),
};
