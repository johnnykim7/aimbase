import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type { ToolContract, ToolResult, ToolExecution } from "../types/tool";

export const toolsApi = {
  list: (params?: { page?: number; size?: number; tag?: string }) =>
    apiClient.get<ApiResponse<ToolContract[]>>("/tools", { params }),

  getContract: (toolName: string) =>
    apiClient.get<ApiResponse<ToolContract>>(`/tools/${toolName}/contract`),

  execute: (toolName: string, body: { input: Record<string, unknown>; context?: Record<string, unknown> }) =>
    apiClient.post<ApiResponse<ToolResult>>(`/tools/${toolName}/execute`, body),

  validate: (toolName: string, body: { input: Record<string, unknown> }) =>
    apiClient.post<ApiResponse<{ valid: boolean; errors?: string[] }>>(`/tools/${toolName}/validate`, body),

  executions: (params?: { session_id?: string; page?: number; size?: number }) =>
    apiClient.get<ApiResponse<ToolExecution[]>>("/tool-executions", { params }),
};
