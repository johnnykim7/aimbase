import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { KnowledgeSource, IngestionLog, SearchRequest, SearchResult } from "../types/knowledge";

export const knowledgeApi = {
  list: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<KnowledgeSource> | KnowledgeSource[]>>("/knowledge-sources", { params }),

  get: (id: string) =>
    apiClient.get<ApiResponse<KnowledgeSource>>(`/knowledge-sources/${id}`),

  create: (data: Partial<KnowledgeSource>) =>
    apiClient.post<ApiResponse<KnowledgeSource>>("/knowledge-sources", data),

  update: (id: string, data: Partial<KnowledgeSource>) =>
    apiClient.put<ApiResponse<KnowledgeSource>>(`/knowledge-sources/${id}`, data),

  delete: (id: string) =>
    apiClient.delete(`/knowledge-sources/${id}`),

  sync: (id: string) =>
    apiClient.post<ApiResponse<{ message: string }>>(`/knowledge-sources/${id}/sync`),

  search: (data: SearchRequest) =>
    apiClient.post<ApiResponse<SearchResult[]>>("/knowledge-sources/search", data),

  ingestionLogs: (id: string, params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<IngestionLog> | IngestionLog[]>>(
      `/knowledge-sources/${id}/ingestion-logs`,
      { params }
    ),
};
