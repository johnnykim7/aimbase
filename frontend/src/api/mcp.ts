import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { MCPServer, MCPServerRequest, DiscoverResult } from "../types/mcp";

export const mcpApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<MCPServer> | MCPServer[]>>("/mcp-servers", { params }),

  get: (id: string) =>
    apiClient.get<ApiResponse<MCPServer>>(`/mcp-servers/${id}`),

  create: (data: MCPServerRequest) =>
    apiClient.post<ApiResponse<MCPServer>>("/mcp-servers", data),

  update: (id: string, data: Partial<MCPServerRequest>) =>
    apiClient.put<ApiResponse<MCPServer>>(`/mcp-servers/${id}`, data),

  delete: (id: string) =>
    apiClient.delete(`/mcp-servers/${id}`),

  discover: (id: string) =>
    apiClient.post<ApiResponse<DiscoverResult>>(`/mcp-servers/${id}/discover`),

  disconnect: (id: string) =>
    apiClient.post<ApiResponse<{ serverId: string; status: string }>>(`/mcp-servers/${id}/disconnect`),
};
