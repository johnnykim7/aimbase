import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";

export interface ModelInfo {
  id: string;
  name?: string;
  provider?: string;
  contextWindow?: number;
  costPer1kInputTokens?: number;
  costPer1kOutputTokens?: number;
}

export interface RoutingConfig {
  id: string;
  name?: string;
  strategy?: string;
  active?: boolean;
  config?: Record<string, unknown>;
  createdAt?: string;
}

export const monitoringApi = {
  models: () =>
    apiClient.get<ApiResponse<ModelInfo[]>>("/models"),

  routing: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<RoutingConfig> | RoutingConfig[]>>("/routing", { params }),

  activeRouting: () =>
    apiClient.get<ApiResponse<RoutingConfig>>("/routing/active"),

  retrievalConfig: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<unknown>>("/retrieval-config", { params }),
};
