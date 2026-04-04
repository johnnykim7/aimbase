import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { connectionGroupsApi } from "../api/connectionGroups";
import type { ConnectionGroup, ConnectionGroupRequest } from "../types/connectionGroup";

export const useConnectionGroups = (params?: { adapter?: string }) =>
  useQuery({
    queryKey: ["connection-groups", params],
    queryFn: () =>
      connectionGroupsApi.list(params).then((r) => {
        const d = r.data.data;
        return Array.isArray(d) ? d : [];
      }),
    retry: false,
  });

export const useConnectionGroup = (id: string) =>
  useQuery({
    queryKey: ["connection-groups", id],
    queryFn: () => connectionGroupsApi.get(id).then((r) => r.data.data as ConnectionGroup),
    enabled: !!id,
  });

export const useCreateConnectionGroup = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: ConnectionGroupRequest) => connectionGroupsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["connection-groups"] }),
  });
};

export const useUpdateConnectionGroup = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: ConnectionGroupRequest }) =>
      connectionGroupsApi.update(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["connection-groups"] }),
  });
};

export const useDeleteConnectionGroup = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => connectionGroupsApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["connection-groups"] }),
  });
};

export const useTestConnectionGroup = () =>
  useMutation({
    mutationFn: (id: string) =>
      connectionGroupsApi.test(id).then((r) => r.data.data),
  });
