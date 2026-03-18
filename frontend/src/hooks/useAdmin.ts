import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "../api/admin";
import type { PagedResponse } from "../types/api";
import type { ActionLog, Approval } from "../types/admin";

export const useDashboard = () =>
  useQuery({
    queryKey: ["dashboard"],
    queryFn: () => adminApi.dashboard().then((r) => r.data.data),
    refetchInterval: 30000,
    retry: false,
  });

export const useActionLogs = () =>
  useQuery({
    queryKey: ["actionLogs"],
    queryFn: async () => {
      const r = await adminApi.actionLogs({ page: 0, size: 30 });
      const d = r.data.data;
      if (d && "content" in d) return (d as PagedResponse<ActionLog>).content;
      return d as ActionLog[];
    },
    refetchInterval: 5000,
    retry: false,
  });

export const useApprovals = () =>
  useQuery({
    queryKey: ["approvals"],
    queryFn: async () => {
      const r = await adminApi.approvals({ page: 0, size: 20 });
      const d = r.data.data;
      if (d && "content" in d) return (d as PagedResponse<Approval>).content;
      return d as Approval[];
    },
    refetchInterval: 10000,
    retry: false,
  });

export const useApprove = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) =>
      adminApi.approve(id, { reason }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["approvals"] });
      qc.invalidateQueries({ queryKey: ["dashboard"] });
    },
  });
};

export const useReject = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) =>
      adminApi.reject(id, { reason }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["approvals"] });
      qc.invalidateQueries({ queryKey: ["dashboard"] });
    },
  });
};

export const useUsage = () =>
  useQuery({
    queryKey: ["usage"],
    queryFn: () => adminApi.usage({ page: 0, size: 50 }).then((r) => r.data.data),
    retry: false,
  });

export const useCostBreakdown = (days: number = 30) =>
  useQuery({
    queryKey: ["admin", "cost-breakdown", days],
    queryFn: () => adminApi.costBreakdown(days).then((r) => r.data.data),
    retry: false,
  });

export const useCostTrend = (days: number = 30) =>
  useQuery({
    queryKey: ["admin", "cost-trend", days],
    queryFn: () => adminApi.costTrend(days).then((r) => r.data.data),
    retry: false,
  });
