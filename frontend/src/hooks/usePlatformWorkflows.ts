import { useQuery } from "@tanstack/react-query";
import { platformWorkflowsApi } from "../api/platformWorkflows";
import type { PlatformWorkflow } from "../types/workflow";

export const usePlatformWorkflows = (category?: string) =>
  useQuery<PlatformWorkflow[]>({
    queryKey: ["platformWorkflows", category],
    queryFn: () =>
      platformWorkflowsApi
        .list(category)
        .then((r) => (r.data.data as PlatformWorkflow[]) ?? []),
    retry: false,
  });
