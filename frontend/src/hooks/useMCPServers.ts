import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { mcpApi } from "../api/mcp";
import type { PagedResponse } from "../types/api";
import type { MCPServer, MCPServerRequest } from "../types/mcp";

const extractList = (d: unknown): MCPServer[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<MCPServer>))
    return (d as PagedResponse<MCPServer>).content;
  return [];
};

export const useMCPServers = () =>
  useQuery({
    queryKey: ["mcpServers"],
    queryFn: () => mcpApi.list().then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useCreateMCPServer = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: MCPServerRequest) => mcpApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["mcpServers"] }),
  });
};

export const useUpdateMCPServer = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<MCPServerRequest> }) =>
      mcpApi.update(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["mcpServers"] }),
  });
};

export const useDeleteMCPServer = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => mcpApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["mcpServers"] }),
  });
};

export const useDiscoverMCPTools = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => mcpApi.discover(id).then((r) => r.data.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["mcpServers"] }),
  });
};

export const useDisconnectMCPServer = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => mcpApi.disconnect(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["mcpServers"] }),
  });
};
