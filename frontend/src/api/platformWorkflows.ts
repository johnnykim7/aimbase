import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type { PlatformWorkflow } from "../types/workflow";

export const platformWorkflowsApi = {
  list: (category?: string) =>
    apiClient.get<ApiResponse<PlatformWorkflow[]>>("/platform/workflows", {
      params: category ? { category } : undefined,
    }),

  get: (id: string) =>
    apiClient.get<ApiResponse<PlatformWorkflow>>(`/platform/workflows/${id}`),
};
