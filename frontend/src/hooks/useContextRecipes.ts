import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { contextRecipesApi } from "../api/contextRecipes";
import type { PagedResponse } from "../types/api";
import type { ContextRecipe, ContextRecipeRequest } from "../types/contextRecipe";

const extractList = (d: unknown): ContextRecipe[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<ContextRecipe>))
    return (d as PagedResponse<ContextRecipe>).content;
  return [];
};

export const useContextRecipes = (params?: { domain_app?: string }) =>
  useQuery({
    queryKey: ["context-recipes", params],
    queryFn: () => contextRecipesApi.list(params).then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useCreateContextRecipe = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: ContextRecipeRequest) => contextRecipesApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["context-recipes"] }),
  });
};

export const useUpdateContextRecipe = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<ContextRecipeRequest> }) =>
      contextRecipesApi.update(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["context-recipes"] }),
  });
};

export const useDeleteContextRecipe = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => contextRecipesApi.remove(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["context-recipes"] }),
  });
};
