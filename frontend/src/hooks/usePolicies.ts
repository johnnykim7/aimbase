import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { policiesApi } from "../api/policies";
import type { PagedResponse } from "../types/api";
import type { Policy, PolicyRequest, SimulateRequest } from "../types/policy";

const extractList = (d: unknown): Policy[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<Policy>))
    return (d as PagedResponse<Policy>).content;
  return [];
};

export const usePolicies = () =>
  useQuery({
    queryKey: ["policies"],
    queryFn: () => policiesApi.list().then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useCreatePolicy = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: PolicyRequest) => policiesApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["policies"] }),
  });
};

export const useUpdatePolicy = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<PolicyRequest> }) =>
      policiesApi.update(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["policies"] }),
  });
};

export const useDeletePolicy = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => policiesApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["policies"] }),
  });
};

export const useSimulatePolicy = () =>
  useMutation({
    mutationFn: ({ id, data }: { id: string; data: SimulateRequest }) =>
      policiesApi.simulate(id, data).then((r) => r.data.data),
  });
