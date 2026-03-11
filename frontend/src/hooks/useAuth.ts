import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authApi } from "../api/auth";
import type { PagedResponse } from "../types/api";
import type { User, UserRequest, Role, RoleRequest } from "../types/auth";

const extractList = <T>(d: unknown): T[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<T>)) return (d as PagedResponse<T>).content;
  return [];
};

export const useUsers = () =>
  useQuery({
    queryKey: ["users"],
    queryFn: () => authApi.listUsers().then((r) => extractList<User>(r.data.data)),
    retry: false,
  });

export const useCreateUser = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: UserRequest) => authApi.createUser(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });
};

export const useDeleteUser = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => authApi.deleteUser(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });
};

export const useGenerateApiKey = () =>
  useMutation({
    mutationFn: (id: string) => authApi.generateApiKey(id).then((r) => r.data.data),
  });

export const useRoles = () =>
  useQuery({
    queryKey: ["roles"],
    queryFn: () => authApi.listRoles().then((r) => extractList<Role>(r.data.data)),
    retry: false,
  });

export const useCreateRole = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: RoleRequest) => authApi.createRole(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["roles"] }),
  });
};

export const useDeleteRole = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => authApi.deleteRole(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["roles"] }),
  });
};
