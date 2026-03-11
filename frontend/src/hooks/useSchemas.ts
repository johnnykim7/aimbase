import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { schemasApi } from "../api/schemas";
import type { PagedResponse } from "../types/api";
import type { Schema, SchemaRequest } from "../types/schema";

const extractList = (d: unknown): Schema[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<Schema>))
    return (d as PagedResponse<Schema>).content;
  return [];
};

export const useSchemas = () =>
  useQuery({
    queryKey: ["schemas"],
    queryFn: () => schemasApi.list().then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useCreateSchema = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: SchemaRequest) => schemasApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["schemas"] }),
  });
};

export const useUpdateSchema = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, data }: { id: string; version: string; data: Partial<SchemaRequest> }) =>
      schemasApi.update(id, version, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["schemas"] }),
  });
};

export const useDeleteSchema = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version?: string }) =>
      schemasApi.delete(id, version),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["schemas"] }),
  });
};

export const useValidateSchema = () =>
  useMutation({
    mutationFn: ({ id, version, data }: { id: string; version: string; data: Record<string, unknown> }) =>
      schemasApi.validate(id, version, data).then((r) => r.data.data),
  });
