import { useQuery } from "@tanstack/react-query";
import { sessionsApi } from "../api/sessions";
import type { PagedResponse } from "../types/api";
import type { SessionMeta } from "../types/session";
import type { ToolExecution } from "../types/tool";

const extractList = (d: unknown): SessionMeta[] => {
  if (!d) return [];
  if (Array.isArray(d)) return d;
  if ("content" in (d as PagedResponse<SessionMeta>))
    return (d as PagedResponse<SessionMeta>).content;
  return [];
};

export const useSessions = (params?: { scope_type?: string; runtime_kind?: string }) =>
  useQuery({
    queryKey: ["sessions", params],
    queryFn: () => sessionsApi.list(params).then((r) => extractList(r.data.data)),
    retry: false,
  });

export const useSessionMeta = (sessionId: string) =>
  useQuery({
    queryKey: ["sessions", sessionId, "meta"],
    queryFn: () => sessionsApi.getMeta(sessionId).then((r) => r.data.data),
    enabled: !!sessionId,
    retry: false,
  });

export const useToolLineage = (sessionId: string) =>
  useQuery({
    queryKey: ["sessions", sessionId, "lineage"],
    queryFn: () =>
      sessionsApi.getLineage(sessionId).then((r) => {
        const d = r.data.data;
        if (Array.isArray(d)) return d as ToolExecution[];
        return [];
      }),
    enabled: !!sessionId,
    retry: false,
  });
