import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { ActionLog, AuditLog, Approval, DashboardStats, UsageStat, CostBreakdown, CostTrendPoint } from "../types/admin";

export const adminApi = {
  dashboard: () =>
    apiClient.get<ApiResponse<DashboardStats>>("/admin/dashboard"),

  actionLogs: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<ActionLog>>>("/admin/action-logs", { params }),

  auditLogs: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<AuditLog>>>("/admin/audit-logs", { params }),

  usage: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<UsageStat> | UsageStat[]>>("/admin/usage", { params }),

  approvals: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<Approval> | Approval[]>>("/admin/approvals", { params }),

  approve: (id: string, data?: { reason?: string; approved_by?: string }) =>
    apiClient.post<ApiResponse<Approval>>(`/admin/approvals/${id}/approve`, data ?? {}),

  reject: (id: string, data?: { reason?: string; rejected_by?: string }) =>
    apiClient.post<ApiResponse<Approval>>(`/admin/approvals/${id}/reject`, data ?? {}),

  costBreakdown: (days: number = 30) =>
    apiClient.get<ApiResponse<CostBreakdown[]>>(`/admin/cost-breakdown`, { params: { days } }),

  costTrend: (days: number = 30) =>
    apiClient.get<ApiResponse<CostTrendPoint[]>>(`/admin/cost-trend`, { params: { days } }),
};
