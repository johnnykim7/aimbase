import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { domainConfigsApi } from "../api/domainConfigs";
import type { DomainAppConfig } from "../types/domain";

export const useDomainConfigs = () =>
  useQuery({
    queryKey: ["domain-configs"],
    queryFn: () => domainConfigsApi.list().then((r) => {
      const d = r.data.data;
      return Array.isArray(d) ? d : [];
    }),
    retry: false,
  });

export const useCreateDomainConfig = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: Omit<DomainAppConfig, "id" | "createdAt" | "updatedAt">) =>
      domainConfigsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["domain-configs"] }),
  });
};

export const useUpdateDomainConfig = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ domainApp, data }: { domainApp: string; data: Partial<Omit<DomainAppConfig, "id" | "createdAt" | "updatedAt">> }) =>
      domainConfigsApi.update(domainApp, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["domain-configs"] }),
  });
};

export const useDeleteDomainConfig = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (domainApp: string) => domainConfigsApi.remove(domainApp),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["domain-configs"] }),
  });
};
