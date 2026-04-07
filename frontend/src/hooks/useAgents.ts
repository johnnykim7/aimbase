import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { agentsApi, type SubagentRunRequest, type OrchestrateRequest } from "../api/agents";

/**
 * CR-030: 서브에이전트 React Query 훅
 */

export const useAgentStatus = (runId: string | null) =>
  useQuery({
    queryKey: ["agent", runId],
    queryFn: () => agentsApi.getStatus(runId!).then((r) => r.data.data),
    enabled: !!runId,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === "RUNNING" ? 3000 : false;
    },
  });

export const useAgentsBySession = (parentSessionId: string | null) =>
  useQuery({
    queryKey: ["agents", "session", parentSessionId],
    queryFn: () => agentsApi.listBySession(parentSessionId!).then((r) => r.data.data),
    enabled: !!parentSessionId,
  });

export const useActiveAgents = () =>
  useQuery({
    queryKey: ["agents", "active"],
    queryFn: () => agentsApi.getActive().then((r) => r.data.data),
    refetchInterval: 10_000,
  });

export const useRunAgent = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: SubagentRunRequest) =>
      agentsApi.run(data).then((r) => r.data.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["agents"] });
    },
  });
};

export const useOrchestrateAgents = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: OrchestrateRequest) =>
      agentsApi.orchestrate(data).then((r) => r.data.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["agents"] });
    },
  });
};

export const useCancelAgent = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) =>
      agentsApi.cancel(runId).then((r) => r.data.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["agents"] });
    },
  });
};
