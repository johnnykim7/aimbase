import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface Skill {
  id: string;
  name: string;
  description: string;
  system_prompt: string;
  tools: string[];
  tags: string[];
  is_active: boolean;
}

export const skillsApi = {
  list: () =>
    apiClient.get<ApiResponse<Skill[]>>("/skills"),

  get: (id: string) =>
    apiClient.get<ApiResponse<Skill>>(`/skills/${id}`),

  create: (body: Partial<Skill>) =>
    apiClient.post<ApiResponse<Skill>>("/skills", body),

  update: (id: string, body: Partial<Skill>) =>
    apiClient.put<ApiResponse<Skill>>(`/skills/${id}`, body),

  delete: (id: string) =>
    apiClient.delete<ApiResponse<{ deleted: string }>>(`/skills/${id}`),
};
