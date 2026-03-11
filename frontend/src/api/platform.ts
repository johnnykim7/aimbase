import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Tenant, TenantRequest, Subscription, PlatformUsage } from "../types/tenant";

export const platformApi = {
  listTenants: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<Tenant> | Tenant[]>>("/platform/tenants", { params }),

  getTenant: (id: string) =>
    apiClient.get<ApiResponse<Tenant>>(`/platform/tenants/${id}`),

  createTenant: (data: TenantRequest) =>
    apiClient.post<ApiResponse<Tenant>>("/platform/tenants", data),

  updateTenant: (id: string, data: Partial<TenantRequest>) =>
    apiClient.put<ApiResponse<Tenant>>(`/platform/tenants/${id}`, data),

  deleteTenant: (id: string) =>
    apiClient.delete(`/platform/tenants/${id}`),

  suspendTenant: (id: string) =>
    apiClient.post<ApiResponse<Tenant>>(`/platform/tenants/${id}/suspend`),

  activateTenant: (id: string) =>
    apiClient.post<ApiResponse<Tenant>>(`/platform/tenants/${id}/activate`),

  listSubscriptions: () =>
    apiClient.get<ApiResponse<PagedResponse<Subscription> | Subscription[]>>("/platform/subscriptions"),

  updateSubscription: (tenantId: string, data: Partial<Subscription>) =>
    apiClient.put<ApiResponse<Subscription>>(`/platform/subscriptions/${tenantId}`, data),

  platformUsage: () =>
    apiClient.get<ApiResponse<PlatformUsage>>("/platform/usage"),
};
