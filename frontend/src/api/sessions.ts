import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { SessionMeta } from "../types/session";
import type { ToolExecution } from "../types/tool";

/* CR-033: Plan/Todo/Task 타입 */
export interface PlanData {
  plan_id: string;
  title: string;
  status: string;
  goals: string[];
  steps: { id: string; description: string; tools?: string[] }[];
  constraints?: string[];
  verification_result?: { completion_rate: number; verified_steps: number; total_steps: number; gaps: { step_id: string; issue: string }[] };
  created_at: string;
  updated_at: string;
}
export interface TodoItem { content: string; status: "pending" | "in_progress" | "completed"; activeForm: string }
export interface TaskData {
  task_id: string; status: string; description: string; priority: string;
  duration_ms: number; token_usage: { input_tokens: number; output_tokens: number };
  created_at: string | null; completed_at: string | null;
  output?: string; large_output?: Record<string, unknown>; error?: string;
}

/* CR-038: Brief 타입 */
export interface BriefData {
  session_id: string;
  summary: string;
  key_decisions: string[];
  pending_items: string[];
  message_count: number;
  model_used: string;
  created_at: string;
}

export const sessionsApi = {
  list: (params?: { page?: number; size?: number; scope_type?: string; runtime_kind?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<SessionMeta> | SessionMeta[]>>("/conversations", { params }),

  getMeta: (sessionId: string) =>
    apiClient.get<ApiResponse<SessionMeta>>(`/conversations/${sessionId}/meta`),

  updateMeta: (sessionId: string, body: Partial<SessionMeta>) =>
    apiClient.put<ApiResponse<SessionMeta>>(`/conversations/${sessionId}/meta`, body),

  getLineage: (sessionId: string) =>
    apiClient.get<ApiResponse<ToolExecution[]>>("/tool-executions", { params: { session_id: sessionId } }),

  // CR-033: Plan/Todo/Task
  getPlan: (sessionId: string) =>
    apiClient.get<ApiResponse<PlanData | null>>(`/sessions/${sessionId}/plan`),
  getTodos: (sessionId: string) =>
    apiClient.get<ApiResponse<TodoItem[]>>(`/sessions/${sessionId}/todos`),
  getTasks: (sessionId: string) =>
    apiClient.get<ApiResponse<TaskData[]>>(`/sessions/${sessionId}/tasks`),

  // CR-038: Brief
  getBrief: (sessionId: string) =>
    apiClient.get<ApiResponse<BriefData | null>>(`/sessions/${sessionId}/brief`),
  createBrief: (sessionId: string) =>
    apiClient.post<ApiResponse<BriefData>>(`/sessions/${sessionId}/brief`),
};
