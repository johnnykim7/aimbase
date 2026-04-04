import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type {
  App, AppCreateRequest, AppUpdateRequest,
  Tenant, TenantRequest, Subscription, PlatformUsage,
  AppTenantCreateRequest, ApiKey, CreateApiKeyRequest,
} from "../types/tenant";
import type {
  AccountPoolStatus, AgentAccount, AgentAccountCreateRequest,
  AgentAccountAssignment, AssignmentCreateRequest,
  GuideEntry, GuideDetail,
} from "../types/agentAccount";

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

  // ─── Subscription ───────────────────────────────────────────────

  listSubscriptions: () =>
    apiClient.get<ApiResponse<PagedResponse<Subscription> | Subscription[]>>("/platform/subscriptions"),

  updateSubscription: (tenantId: string, data: Partial<Subscription>) =>
    apiClient.put<ApiResponse<Subscription>>(`/platform/subscriptions/${tenantId}`, data),

  // ─── Usage ──────────────────────────────────────────────────────

  platformUsage: () =>
    apiClient.get<ApiResponse<PlatformUsage>>("/platform/usage"),

  // ─── Agent Account Pool ────────────────────────────────────────

  listAgentAccounts: () =>
    apiClient.get<AccountPoolStatus[]>("/platform/agent-accounts"),

  getAgentAccount: (id: string) =>
    apiClient.get<AgentAccount>(`/platform/agent-accounts/${id}`),

  createAgentAccount: (data: AgentAccountCreateRequest) =>
    apiClient.post<AgentAccount>("/platform/agent-accounts", data),

  updateAgentAccount: (id: string, data: Partial<AgentAccountCreateRequest>) =>
    apiClient.put<AgentAccount>(`/platform/agent-accounts/${id}`, data),

  deleteAgentAccount: (id: string) =>
    apiClient.delete(`/platform/agent-accounts/${id}`),

  testAgentAccount: (id: string) =>
    apiClient.post<{ status: string; response?: string; message?: string }>(`/platform/agent-accounts/${id}/test`),

  resetCircuit: (id: string) =>
    apiClient.post<{ account_id: string; circuit_state: string }>(`/platform/agent-accounts/${id}/reset-circuit`),

  saveToken: (id: string, data: { auth_type: string; auth_token: string }) =>
    apiClient.post<{ status: string; auth_type: string; message: string }>(`/platform/agent-accounts/${id}/save-token`, data),

  listAssignments: () =>
    apiClient.get<AgentAccountAssignment[]>("/platform/agent-accounts/assignments"),

  createAssignment: (data: AssignmentCreateRequest) =>
    apiClient.post<AgentAccountAssignment>("/platform/agent-accounts/assignments", data),

  deleteAssignment: (id: number) =>
    apiClient.delete(`/platform/agent-accounts/assignments/${id}`),

  // ─── Guides (매뉴얼) ───────────────────────────────────────────

  listGuides: () =>
    apiClient.get<GuideEntry[]>("/guides"),

  getGuide: (slug: string) =>
    apiClient.get<GuideDetail>(`/guides/${slug}`),

  // API Keys (CR-025)
  listApiKeys: (tenantId?: string) =>
    apiClient.get<ApiResponse<ApiKey[]>>("/platform/api-keys", { params: tenantId ? { tenantId } : undefined }),

  createApiKey: (data: CreateApiKeyRequest) =>
    apiClient.post<ApiResponse<ApiKey & { apiKey: string }>>("/platform/api-keys", data),

  revokeApiKey: (id: string) =>
    apiClient.delete(`/platform/api-keys/${id}`),

  regenerateApiKey: (id: string) =>
    apiClient.post<ApiResponse<ApiKey & { apiKey: string }>>(`/platform/api-keys/${id}/regenerate`),
};
