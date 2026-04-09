import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { sessionsApi } from "../api/sessions";
import type { PlanData, TodoItem, TaskData, BriefData } from "../api/sessions";
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

// CR-033: Plan/Todo/Task hooks
export const useSessionPlan = (sessionId: string) =>
  useQuery({
    queryKey: ["sessions", sessionId, "plan"],
    queryFn: () => sessionsApi.getPlan(sessionId).then((r) => r.data.data as PlanData | null),
    enabled: !!sessionId,
    retry: false,
  });

export const useSessionTodos = (sessionId: string) =>
  useQuery({
    queryKey: ["sessions", sessionId, "todos"],
    queryFn: () => sessionsApi.getTodos(sessionId).then((r) => (r.data.data ?? []) as TodoItem[]),
    enabled: !!sessionId,
    retry: false,
  });

export const useSessionTasks = (sessionId: string) =>
  useQuery({
    queryKey: ["sessions", sessionId, "tasks"],
    queryFn: () => sessionsApi.getTasks(sessionId).then((r) => (r.data.data ?? []) as TaskData[]),
    enabled: !!sessionId,
    retry: false,
  });

// CR-038: Brief hooks
export const useSessionBrief = (sessionId: string) =>
  useQuery({
    queryKey: ["sessions", sessionId, "brief"],
    queryFn: () => sessionsApi.getBrief(sessionId).then((r) => r.data.data as BriefData | null),
    enabled: !!sessionId,
    retry: false,
  });

export const useCreateBrief = (sessionId: string) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => sessionsApi.createBrief(sessionId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["sessions", sessionId, "brief"] }),
  });
};
