import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { platformApi } from "../api/platform";
import type { PagedResponse } from "../types/api";
import type { App, AppCreateRequest, AppUpdateRequest, Tenant, TenantRequest, Subscription, AppTenantCreateRequest } from "../types/tenant";
import type { AccountPoolStatus, AgentAccountCreateRequest, AssignmentCreateRequest, AgentAccountAssignment, GuideEntry, GuideDetail } from "../types/agentAccount";

const extractList = <T>(d: unknown): T[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<T>)) return (d as PagedResponse<T>).content;
  return [];
};

export const useTenants = () =>
  useQuery({
    queryKey: ["tenants"],
    queryFn: () => platformApi.listTenants().then((r) => extractList<Tenant>(r.data.data)),
    retry: false,
  });

export const useCreateTenant = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: TenantRequest) => platformApi.createTenant(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["tenants"] }),
  });
};

export const useUpdateTenant = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<TenantRequest> }) =>
      platformApi.updateTenant(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["tenants"] }),
  });
};

export const useDeleteTenant = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => platformApi.deleteTenant(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["tenants"] }),
  });
};

export const useSuspendTenant = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => platformApi.suspendTenant(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["tenants"] }),
  });
};

export const useActivateTenant = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => platformApi.activateTenant(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["tenants"] }),
  });
};

export const useSubscriptions = () =>
  useQuery({
    queryKey: ["subscriptions"],
    queryFn: () =>
      platformApi.listSubscriptions().then((r) => extractList<Subscription>(r.data.data)),
    retry: false,
  });

export const useUpdateSubscription = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ tenantId, data }: { tenantId: string; data: Partial<Subscription> }) =>
      platformApi.updateSubscription(tenantId, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["subscriptions"] }),
  });
};

export const usePlatformUsage = () =>
  useQuery({
    queryKey: ["platformUsage"],
    queryFn: () => platformApi.platformUsage().then((r) => r.data.data),
    retry: false,
  });

// ─── App (소비앱) Hooks ─────────────────────────────────────────

export const useApps = () =>
  useQuery({
    queryKey: ["apps"],
    queryFn: () => platformApi.listApps().then((r) => extractList<App>(r.data.data)),
    retry: false,
  });

export const useCreateApp = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: AppCreateRequest) => platformApi.createApp(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apps"] }),
  });
};

export const useUpdateApp = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ appId, data }: { appId: string; data: AppUpdateRequest }) =>
      platformApi.updateApp(appId, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apps"] }),
  });
};

export const useDeleteApp = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (appId: string) => platformApi.deleteApp(appId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apps"] }),
  });
};

export const useSuspendApp = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (appId: string) => platformApi.suspendApp(appId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apps"] }),
  });
};

export const useActivateApp = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (appId: string) => platformApi.activateApp(appId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apps"] }),
  });
};

export const useAppTenants = (appId: string) =>
  useQuery({
    queryKey: ["appTenants", appId],
    queryFn: () => platformApi.listAppTenants(appId).then((r) => extractList<Tenant>(r.data.data)),
    enabled: !!appId,
    retry: false,
  });

export const useAppCreateTenant = (appId: string) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: AppTenantCreateRequest) => platformApi.appCreateTenant(appId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["appTenants", appId] });
      qc.invalidateQueries({ queryKey: ["apps"] });
    },
  });
};

// ─── Agent Account Pool Hooks ──────────────────────────────────

export const useAgentAccounts = () =>
  useQuery({
    queryKey: ["agentAccounts"],
    queryFn: () => platformApi.listAgentAccounts().then((r) => r.data as AccountPoolStatus[]),
    retry: false,
  });

export const useCreateAgentAccount = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: AgentAccountCreateRequest) => platformApi.createAgentAccount(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agentAccounts"] }),
  });
};

export const useUpdateAgentAccount = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<AgentAccountCreateRequest> }) =>
      platformApi.updateAgentAccount(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agentAccounts"] }),
  });
};

export const useDeleteAgentAccount = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => platformApi.deleteAgentAccount(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agentAccounts"] }),
  });
};

export const useTestAgentAccount = () =>
  useMutation({
    mutationFn: (id: string) => platformApi.testAgentAccount(id).then((r) => r.data),
  });

export const useSaveToken = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: { id: string; auth_type: string; auth_token: string }) =>
      platformApi.saveToken(params.id, { auth_type: params.auth_type, auth_token: params.auth_token }).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agentAccounts"] }),
  });
};

export const useResetCircuit = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => platformApi.resetCircuit(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agentAccounts"] }),
  });
};

export const useAssignments = () =>
  useQuery({
    queryKey: ["agentAssignments"],
    queryFn: () => platformApi.listAssignments().then((r) => r.data as AgentAccountAssignment[]),
    retry: false,
  });

export const useCreateAssignment = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: AssignmentCreateRequest) => platformApi.createAssignment(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agentAssignments"] }),
  });
};

export const useDeleteAssignment = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => platformApi.deleteAssignment(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["agentAssignments"] }),
  });
};

// ─── Guides (매뉴얼) Hooks ─────────────────────────────────────

export const useGuides = () =>
  useQuery({
    queryKey: ["guides"],
    queryFn: () => platformApi.listGuides().then((r) => r.data as GuideEntry[]),
    retry: false,
  });

export const useGuide = (slug: string) =>
  useQuery({
    queryKey: ["guide", slug],
    queryFn: () => platformApi.getGuide(slug).then((r) => r.data as GuideDetail),
    enabled: !!slug,
    retry: false,
    staleTime: 5 * 60 * 1000,
  });
