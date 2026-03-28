import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { workflowsApi } from "../api/workflows";
import type { PagedResponse } from "../types/api";
import type { Workflow, WorkflowRequest } from "../types/workflow";

const extractList = (d: unknown): Workflow[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<Workflow>))
    return (d as PagedResponse<Workflow>).content;
  return [];
};

export const useWorkflows = (my?: boolean) =>
  useQuery({
    queryKey: ["workflows", { my }],
    queryFn: () => workflowsApi.list(my ? { my: true } : undefined).then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useCreateWorkflow = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: WorkflowRequest) => workflowsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["workflows"] }),
  });
};

export const useDeleteWorkflow = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => workflowsApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["workflows"] }),
  });
};

export const useUpdateWorkflow = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<WorkflowRequest> }) =>
      workflowsApi.update(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["workflows"] }),
  });
};

export const useRunWorkflow = () =>
  useMutation({
    mutationFn: ({ id, input }: { id: string; input?: Record<string, unknown> }) =>
      workflowsApi.run(id, input).then((r) => r.data.data),
  });
