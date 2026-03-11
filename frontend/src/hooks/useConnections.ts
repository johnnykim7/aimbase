import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { connectionsApi } from "../api/connections";
import type { PagedResponse } from "../types/api";
import type { Connection, ConnectionRequest } from "../types/connection";

const extractList = (d: unknown): Connection[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<Connection>))
    return (d as PagedResponse<Connection>).content;
  return [];
};

export const useConnections = (params?: { type?: string }) =>
  useQuery({
    queryKey: ["connections", params],
    queryFn: () =>
      connectionsApi.list(params).then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useCreateConnection = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: ConnectionRequest) => connectionsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["connections"] }),
  });
};

export const useUpdateConnection = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<ConnectionRequest> }) =>
      connectionsApi.update(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["connections"] }),
  });
};

export const useDeleteConnection = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => connectionsApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["connections"] }),
  });
};

export const useTestConnection = () =>
  useMutation({
    mutationFn: (id: string) =>
      connectionsApi.test(id).then((r) => r.data.data),
  });
