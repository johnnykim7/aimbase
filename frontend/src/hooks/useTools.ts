import { useQuery, useMutation } from "@tanstack/react-query";
import { toolsApi } from "../api/tools";

export const useTools = (params?: { tag?: string }) =>
  useQuery({
    queryKey: ["tools", params],
    queryFn: () => toolsApi.list(params).then((r) => r.data.data ?? []),
    retry: false,
  });

export const useToolContract = (toolName: string) =>
  useQuery({
    queryKey: ["tools", toolName, "contract"],
    queryFn: () => toolsApi.getContract(toolName).then((r) => r.data.data),
    enabled: !!toolName,
    retry: false,
  });

export const useExecuteTool = () =>
  useMutation({
    mutationFn: ({ toolName, input, context }: {
      toolName: string;
      input: Record<string, unknown>;
      context?: Record<string, unknown>;
    }) => toolsApi.execute(toolName, { input, context }).then((r) => r.data.data),
  });

export const useToolExecutions = (sessionId?: string) =>
  useQuery({
    queryKey: ["tool-executions", sessionId],
    queryFn: () => toolsApi.executions({ session_id: sessionId }).then((r) => r.data.data ?? []),
    enabled: !!sessionId,
    retry: false,
  });
