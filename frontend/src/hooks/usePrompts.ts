import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { promptsApi } from "../api/prompts";
import type { PagedResponse } from "../types/api";
import type { Prompt, PromptRequest, PromptTestRequest } from "../types/prompt";

const extractList = (d: unknown): Prompt[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<Prompt>))
    return (d as PagedResponse<Prompt>).content;
  return [];
};

export const usePrompts = () =>
  useQuery({
    queryKey: ["prompts"],
    queryFn: () => promptsApi.list().then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useCreatePrompt = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: PromptRequest) => promptsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["prompts"] }),
  });
};

export const useUpdatePrompt = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, data }: { id: string; version: string; data: Partial<PromptRequest> }) =>
      promptsApi.update(id, version, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["prompts"] }),
  });
};

export const useDeletePrompt = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version?: string }) =>
      promptsApi.delete(id, version),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["prompts"] }),
  });
};

export const useTestPrompt = () =>
  useMutation({
    mutationFn: ({ id, version, data }: { id: string; version: string; data: PromptTestRequest }) =>
      promptsApi.test(id, version, data).then((r) => r.data.data),
  });
