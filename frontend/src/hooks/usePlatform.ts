import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { platformApi } from "../api/platform";
import type { PagedResponse } from "../types/api";
import type { Tenant, TenantRequest, Subscription, ApiKey, CreateApiKeyRequest } from "../types/tenant";

const extractList = <T>(d: unknown): T[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<T>)) return (d as PagedResponse<T>).content;
  return [];
};

export const useTenants = (domainApp?: string) =>
  useQuery({
    queryKey: ["tenants", { domainApp }],
    queryFn: () => platformApi.listTenants(domainApp ? { domain_app: domainApp } : undefined).then((r) => extractList<Tenant>(r.data.data)),
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

// CR-025: API Keys
export const useApiKeys = (tenantId?: string) =>
  useQuery({
    queryKey: ["apiKeys", { tenantId }],
    queryFn: () => platformApi.listApiKeys(tenantId).then((r) => (r.data.data ?? []) as ApiKey[]),
    retry: false,
  });

export const useCreateApiKey = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateApiKeyRequest) => platformApi.createApiKey(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apiKeys"] }),
  });
};

export const useRevokeApiKey = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => platformApi.revokeApiKey(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apiKeys"] }),
  });
};

export const useRegenerateApiKey = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => platformApi.regenerateApiKey(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["apiKeys"] }),
  });
};
