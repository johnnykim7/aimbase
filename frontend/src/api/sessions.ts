import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { SessionMeta } from "../types/session";
import type { ToolExecution } from "../types/tool";

export const sessionsApi = {
  list: (params?: { page?: number; size?: number; scope_type?: string; runtime_kind?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<SessionMeta> | SessionMeta[]>>("/conversations", { params }),

  getMeta: (sessionId: string) =>
    apiClient.get<ApiResponse<SessionMeta>>(`/conversations/${sessionId}/meta`),

  updateMeta: (sessionId: string, body: Partial<SessionMeta>) =>
    apiClient.put<ApiResponse<SessionMeta>>(`/conversations/${sessionId}/meta`, body),

  getLineage: (sessionId: string) =>
    apiClient.get<ApiResponse<ToolExecution[]>>("/tool-executions", { params: { session_id: sessionId } }),
};
