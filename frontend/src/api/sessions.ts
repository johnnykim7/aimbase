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

/* CR-039: Team 타입 */
export interface TeamMember {
  member_id: string;
  agent_type: string;
  role: string;
  status: "IDLE" | "RUNNING" | "COMPLETED" | "FAILED" | string;
}
export interface TeamData {
  team_id: string;
  name: string;
  status: "ACTIVE" | "COMPLETED" | "DISSOLVED" | string;
  objective?: string;
  result_summary?: string;
  members: TeamMember[];
  created_at: string;
  dissolved_at?: string | null;
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

/* CR-049 PRD-303: Session Resume 응답 */
export interface CompactBoundaryInfo {
  summary?: string | null;
  compacted_count?: number;
  tokens_saved?: number;
  strategy?: string;
  boundary_at?: string;
}

export interface ResumeMessage {
  id: string;
  role: string;
  message_type: "TEXT" | "TOOL_USE" | "TOOL_RESULT" | "COMPACT_BOUNDARY" | string;
  content: string;
  tokens: number;
  created_at: string;
  boundary_meta?: CompactBoundaryInfo;
}

export interface ResumeResponse {
  session_id: string;
  resumed_at: string;
  boundary: CompactBoundaryInfo | null;
  messages: ResumeMessage[];
  preserved_context: Record<string, unknown>;
}

export const sessionsApi = {
  list: (params?: { page?: number; size?: number; scope_type?: string; runtime_kind?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<SessionMeta> | SessionMeta[]>>("/conversations", { params }),

  getMeta: (sessionId: string) =>
    apiClient.get<ApiResponse<SessionMeta>>(`/conversations/${sessionId}/meta`),

  updateMeta: (sessionId: string, body: Partial<SessionMeta>) =>
    apiClient.put<ApiResponse<SessionMeta>>(`/conversations/${sessionId}/meta`, body),

  /** CR-045 follow-up: 빈 세션 사전 생성 (NewChatModal에서 호출) */
  create: (body: { sessionId: string; title?: string; workspaceRef?: string; scopeType?: string }) =>
    apiClient.post<ApiResponse<{ sessionId: string; created: boolean }>>("/conversations", body),

  /** CR-045 follow-up: 세션 삭제 */
  delete: (sessionId: string) =>
    apiClient.delete<void>(`/conversations/${sessionId}`),

  /** CR-045 follow-up: 제목 변경 (updateMeta의 편의 래퍼) */
  updateTitle: (sessionId: string, title: string) =>
    apiClient.put<ApiResponse<{ updated: string }>>(`/conversations/${sessionId}/meta`, { title }),

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

  // CR-049 PRD-303: 세션 재개
  resume: (sessionId: string) =>
    apiClient.post<ApiResponse<ResumeResponse>>(`/sessions/${sessionId}/resume`),
};
