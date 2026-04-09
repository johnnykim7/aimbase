import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { Tenant, TenantRequest, Subscription, PlatformUsage, ApiKey, CreateApiKeyRequest } from "../types/tenant";

export const platformApi = {
  listTenants: (params?: { page?: number; size?: number; domain_app?: string }) =>
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

  // API Keys (CR-025)
  listApiKeys: (tenantId?: string) =>
    apiClient.get<ApiResponse<ApiKey[]>>("/platform/api-keys", { params: tenantId ? { tenantId } : undefined }),

  createApiKey: (data: CreateApiKeyRequest) =>
    apiClient.post<ApiResponse<ApiKey & { apiKey: string }>>("/platform/api-keys", data),

  revokeApiKey: (id: string) =>
    apiClient.delete(`/platform/api-keys/${id}`),

  regenerateApiKey: (id: string) =>
    apiClient.post<ApiResponse<ApiKey & { apiKey: string }>>(`/platform/api-keys/${id}/regenerate`),

  // CR-040: Platform Settings
  getSettings: (category?: string) =>
    apiClient.get<{ success: boolean; data: Record<string, SettingItem[]> }>("/platform/settings", {
      params: category ? { category } : undefined,
    }),

  updateSettings: (settings: { key: string; value: string }[], userId?: string) =>
    apiClient.put<{ success: boolean; updated: number; data: Record<string, SettingItem[]> }>(
      "/platform/settings",
      { settings, userId }
    ),

  evictSettingsCache: () =>
    apiClient.post<{ success: boolean; message: string }>("/platform/settings/evict-cache"),
};

export interface SettingItem {
  key: string;
  value: string;
  description: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
}
