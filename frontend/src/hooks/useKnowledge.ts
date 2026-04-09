import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { knowledgeApi } from "../api/knowledge";
import type { PagedResponse } from "../types/api";
import type { KnowledgeSource, SearchRequest, SearchResult } from "../types/knowledge";

const extractList = (d: unknown): KnowledgeSource[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<KnowledgeSource>))
    return (d as PagedResponse<KnowledgeSource>).content;
  return [];
};

export const useKnowledgeSources = (my?: boolean) =>
  useQuery({
    queryKey: ["knowledgeSources", { my }],
    queryFn: () => knowledgeApi.list(my ? { my: true } : undefined).then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useSyncKnowledge = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => knowledgeApi.sync(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["knowledgeSources"] }),
  });
};

export const useSearchKnowledge = () =>
  useMutation({
    mutationFn: (data: SearchRequest) =>
      knowledgeApi.search(data).then((r) => {
        const d = r.data.data as unknown;
        if (d && typeof d === "object" && "results" in (d as Record<string, unknown>)) {
          return (d as { results: SearchResult[] }).results;
        }
        return (d ?? []) as SearchResult[];
      }),
  });

export const useCreateKnowledgeSource = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<KnowledgeSource>) => knowledgeApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["knowledgeSources"] }),
  });
};

export const useDeleteKnowledgeSource = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => knowledgeApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["knowledgeSources"] }),
  });
};
