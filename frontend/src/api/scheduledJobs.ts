import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface ScheduledJob {
  id: string;
  name: string;
  cron_expression: string;
  target_type: string;
  target_id: string;
  is_active: boolean;
  failure_count: number;
  last_run_status: string;
  last_run_at: string;
  next_run_at: string;
}

export const scheduledJobsApi = {
  list: () =>
    apiClient.get<ApiResponse<ScheduledJob[]>>("/scheduled-jobs"),

  create: (body: {
    name: string;
    cron_expression: string;
    target_type: string;
    target_id: string;
    input?: Record<string, unknown>;
  }) => apiClient.post<ApiResponse<ScheduledJob>>("/scheduled-jobs", body),

  delete: (jobId: string) =>
    apiClient.delete<ApiResponse<{ deleted: string }>>(`/scheduled-jobs/${jobId}`),

  toggle: (jobId: string, isActive: boolean) =>
    apiClient.patch<ApiResponse<ScheduledJob>>(`/scheduled-jobs/${jobId}/toggle`, {
      is_active: isActive,
    }),
};
