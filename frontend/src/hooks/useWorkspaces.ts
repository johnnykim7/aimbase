import { useQuery } from "@tanstack/react-query";
import { workspacesApi } from "../api/workspaces";

/**
 * CR-045: 화이트리스트 루트 하위 디렉토리 목록.
 */
export const useWorkspaces = () =>
  useQuery({
    queryKey: ["workspaces"],
    queryFn: () => workspacesApi.list().then((r) => r.data.data ?? []),
    retry: false,
  });
