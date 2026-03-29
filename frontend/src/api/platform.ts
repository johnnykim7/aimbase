import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type {
  App, AppCreateRequest, AppUpdateRequest,
  Tenant, TenantRequest, Subscription, PlatformUsage,
  AppTenantCreateRequest,
} from "../types/tenant";

export const platformApi = {
  // ─── App (소비앱) 관리 ──────────────────────────────────────────

  listApps: (params?: { status?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<App> | App[]>>("/platform/apps", { params }),

  getApp: (appId: string) =>
    apiClient.get<ApiResponse<{ app: App; tenantCount: number; tenants: Tenant[] }>>(`/platform/apps/${appId}`),

  createApp: (data: AppCreateRequest) =>
    apiClient.post<ApiResponse<App>>("/platform/apps", data),

  updateApp: (appId: string, data: AppUpdateRequest) =>
    apiClient.put<ApiResponse<App>>(`/platform/apps/${appId}`, data),

  deleteApp: (appId: string) =>
    apiClient.delete(`/platform/apps/${appId}`),

  suspendApp: (appId: string) =>
    apiClient.post<ApiResponse<App>>(`/platform/apps/${appId}/suspend`),

  activateApp: (appId: string) =>
    apiClient.post<ApiResponse<App>>(`/platform/apps/${appId}/activate`),

  listAppTenants: (appId: string, params?: { status?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<Tenant> | Tenant[]>>(`/platform/apps/${appId}/tenants`, { params }),

  // ─── 소비앱 어드민: Tenant 셀프서비스 ───────────────────────────

  appCreateTenant: (appId: string, data: AppTenantCreateRequest) =>
    apiClient.post<ApiResponse<Tenant>>(`/apps/${appId}/tenants`, data),

  appListTenants: (appId: string, params?: { status?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<Tenant> | Tenant[]>>(`/apps/${appId}/tenants`, { params }),

  appSuspendTenant: (appId: string, tenantId: string) =>
    apiClient.post<ApiResponse<Tenant>>(`/apps/${appId}/tenants/${tenantId}/suspend`),

  appActivateTenant: (appId: string, tenantId: string) =>
    apiClient.post<ApiResponse<Tenant>>(`/apps/${appId}/tenants/${tenantId}/activate`),

  appDeleteTenant: (appId: string, tenantId: string) =>
    apiClient.delete(`/apps/${appId}/tenants/${tenantId}`),

  // ─── Tenant 관리 (기존) ─────────────────────────────────────────

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

  // ─── Subscription ───────────────────────────────────────────────

  listSubscriptions: () =>
    apiClient.get<ApiResponse<PagedResponse<Subscription> | Subscription[]>>("/platform/subscriptions"),

  updateSubscription: (tenantId: string, data: Partial<Subscription>) =>
    apiClient.put<ApiResponse<Subscription>>(`/platform/subscriptions/${tenantId}`, data),

  // ─── Usage ──────────────────────────────────────────────────────

  platformUsage: () =>
    apiClient.get<ApiResponse<PlatformUsage>>("/platform/usage"),
};
