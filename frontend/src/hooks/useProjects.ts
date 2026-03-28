import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { projectsApi } from "../api/projects";
import type { Project, ProjectRequest, MemberRequest, ResourceRequest } from "../types/project";

export const useProjects = () =>
  useQuery({
    queryKey: ["projects"],
    queryFn: () => projectsApi.list().then((r) => (r.data.data ?? []) as Project[]),
    retry: false,
  });

export const useProject = (id: string | undefined) =>
  useQuery({
    queryKey: ["projects", id],
    queryFn: () => projectsApi.get(id!).then((r) => r.data.data),
    enabled: !!id,
  });

export const useCreateProject = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: ProjectRequest) => projectsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["projects"] }),
  });
};

export const useUpdateProject = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<ProjectRequest> }) =>
      projectsApi.update(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["projects"] }),
  });
};

export const useDeleteProject = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => projectsApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["projects"] }),
  });
};

export const useAddMember = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ projectId, data }: { projectId: string; data: MemberRequest }) =>
      projectsApi.addMember(projectId, data),
    onSuccess: (_, { projectId }) =>
      qc.invalidateQueries({ queryKey: ["projects", projectId] }),
  });
};

export const useRemoveMember = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ projectId, userId }: { projectId: string; userId: string }) =>
      projectsApi.removeMember(projectId, userId),
    onSuccess: (_, { projectId }) =>
      qc.invalidateQueries({ queryKey: ["projects", projectId] }),
  });
};

export const useAssignResource = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ projectId, data }: { projectId: string; data: ResourceRequest }) =>
      projectsApi.assignResource(projectId, data),
    onSuccess: (_, { projectId }) =>
      qc.invalidateQueries({ queryKey: ["projects", projectId] }),
  });
};

export const useRemoveResource = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ projectId, resourceType, resourceId }: {
      projectId: string; resourceType: string; resourceId: string;
    }) => projectsApi.removeResource(projectId, resourceType, resourceId),
    onSuccess: (_, { projectId }) =>
      qc.invalidateQueries({ queryKey: ["projects", projectId] }),
  });
};
