import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { ContextRecipe, ContextRecipeRequest } from "../types/contextRecipe";

export const contextRecipesApi = {
  list: (params?: { page?: number; size?: number; domain_app?: string }) =>
    apiClient.get<ApiResponse<PagedResponse<ContextRecipe> | ContextRecipe[]>>("/context-recipes", { params }),

  get: (id: string) =>
    apiClient.get<ApiResponse<ContextRecipe>>(`/context-recipes/${id}`),

  create: (data: ContextRecipeRequest) =>
    apiClient.post<ApiResponse<ContextRecipe>>("/context-recipes", data),

  update: (id: string, data: Partial<ContextRecipeRequest>) =>
    apiClient.put<ApiResponse<ContextRecipe>>(`/context-recipes/${id}`, data),

  remove: (id: string) =>
    apiClient.delete(`/context-recipes/${id}`),
};
