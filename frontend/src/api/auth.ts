import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types/api";
import type { User, UserRequest, Role, RoleRequest } from "../types/auth";

export const authApi = {
  listUsers: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<User> | User[]>>("/users", { params }),

  getUser: (id: string) =>
    apiClient.get<ApiResponse<User>>(`/users/${id}`),

  createUser: (data: UserRequest) =>
    apiClient.post<ApiResponse<User>>("/users", data),

  updateUser: (id: string, data: Partial<UserRequest>) =>
    apiClient.put<ApiResponse<User>>(`/users/${id}`, data),

  deleteUser: (id: string) =>
    apiClient.delete(`/users/${id}`),

  generateApiKey: (id: string) =>
    apiClient.post<ApiResponse<{ apiKey: string }>>(`/users/${id}/api-key`),

  listRoles: (params?: { page?: number; size?: number }) =>
    apiClient.get<ApiResponse<PagedResponse<Role> | Role[]>>("/roles", { params }),

  getRole: (id: string) =>
    apiClient.get<ApiResponse<Role>>(`/roles/${id}`),

  createRole: (data: RoleRequest) =>
    apiClient.post<ApiResponse<Role>>("/roles", data),

  updateRole: (id: string, data: Partial<RoleRequest>) =>
    apiClient.put<ApiResponse<Role>>(`/roles/${id}`, data),

  deleteRole: (id: string) =>
    apiClient.delete(`/roles/${id}`),
};
