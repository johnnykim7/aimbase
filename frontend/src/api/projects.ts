import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type {
  Project,
  ProjectDetail,
  ProjectRequest,
  MemberRequest,
  ProjectMember,
  ResourceRequest,
  ProjectResource,
} from "../types/project";

export const projectsApi = {
  list: () =>
    apiClient.get<ApiResponse<Project[]>>("/projects"),

  get: (id: string) =>
    apiClient.get<ApiResponse<ProjectDetail>>(`/projects/${id}`),

  create: (data: ProjectRequest) =>
    apiClient.post<ApiResponse<Project>>("/projects", data),

  update: (id: string, data: Partial<ProjectRequest>) =>
    apiClient.put<ApiResponse<Project>>(`/projects/${id}`, data),

  delete: (id: string) =>
    apiClient.delete(`/projects/${id}`),

  // 멤버
  listMembers: (id: string) =>
    apiClient.get<ApiResponse<ProjectMember[]>>(`/projects/${id}/members`),

  addMember: (id: string, data: MemberRequest) =>
    apiClient.post<ApiResponse<ProjectMember>>(`/projects/${id}/members`, data),

  removeMember: (id: string, userId: string) =>
    apiClient.delete(`/projects/${id}/members/${userId}`),

  // 리소스
  listResources: (id: string, resourceType?: string) =>
    apiClient.get<ApiResponse<ProjectResource[]>>(`/projects/${id}/resources`, {
      params: resourceType ? { resource_type: resourceType } : undefined,
    }),

  assignResource: (id: string, data: ResourceRequest) =>
    apiClient.post<ApiResponse<ProjectResource>>(`/projects/${id}/resources`, data),

  removeResource: (id: string, resourceType: string, resourceId: string) =>
    apiClient.delete(`/projects/${id}/resources/${resourceType}/${resourceId}`),
};
