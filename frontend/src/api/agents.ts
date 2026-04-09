import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

// ── 타입 정의 ──

export interface SubagentRunRequest {
  description: string;
  prompt: string;
  model?: string;
  connectionId?: string;
  isolation?: "NONE" | "WORKTREE";
  runInBackground?: boolean;
  timeoutMs?: number;
  config?: Record<string, unknown>;
  parentSessionId?: string;
  agentType?: AgentTypeName;
}

/** CR-034: Built-in 에이전트 타입 */
export type AgentTypeName = "GENERAL" | "PLAN" | "EXPLORE" | "GUIDE" | "VERIFICATION";

export interface AgentTypeSummary {
  name: AgentTypeName;
  displayName: string;
  description: string;
  readOnly: boolean;
  allowedTools: string | null;
}

/** CR-034: 에이전트 간 메시지 */
export interface AgentMessage {
  id: string;
  sessionId: string;
  fromAgentId: string;
  toAgentId: string;
  messageType: "TEXT" | "COMMAND" | "RESULT" | "ERROR";
  content: string;
  metadata?: Record<string, unknown>;
  read: boolean;
  createdAt: string;
}

export interface SubagentResult {
  subagentRunId: string;
  sessionId: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | "TIMEOUT" | "CANCELLED";
  output?: string;
  structuredData?: Record<string, unknown>;
  exitCode: number;
  usage?: { inputTokens: number; outputTokens: number };
  worktreePath?: string;
  branchName?: string;
  durationMs: number;
  startedAt?: string;
  completedAt?: string;
  error?: string;
}

export interface OrchestrateRequest {
  agents: SubagentRunRequest[];
  execution?: "parallel" | "sequential";
  parentSessionId?: string;
}

export interface OrchestratedResult {
  results: SubagentResult[];
  mergedOutput: string;
  totalUsage: { inputTokens: number; outputTokens: number };
  maxDurationMs: number;
  successCount: number;
  failCount: number;
}

// ── API 클라이언트 ──

export const agentsApi = {
  run: (data: SubagentRunRequest) =>
    apiClient.post<ApiResponse<SubagentResult>>("/agents/run", data),

  orchestrate: (data: OrchestrateRequest) =>
    apiClient.post<ApiResponse<OrchestratedResult>>("/agents/orchestrate", data),

  getStatus: (runId: string) =>
    apiClient.get<ApiResponse<SubagentResult>>(`/agents/${runId}`),

  listBySession: (parentSessionId: string) =>
    apiClient.get<ApiResponse<SubagentResult[]>>(`/agents/session/${parentSessionId}`),

  cancel: (runId: string) =>
    apiClient.post<ApiResponse<{ runId: string; cancelled: boolean }>>(`/agents/${runId}/cancel`),

  getActive: () =>
    apiClient.get<ApiResponse<{ activeCount: number; agents: string[] }>>("/agents/active"),

  // CR-034: 에이전트 타입
  listTypes: () =>
    apiClient.get<ApiResponse<AgentTypeSummary[]>>("/agents/types"),

  // CR-034: 에이전트 메시지
  getSessionMessages: (sessionId: string) =>
    apiClient.get<ApiResponse<AgentMessage[]>>(`/agents/messages/${sessionId}`),

  getAgentMessages: (sessionId: string, agentId: string) =>
    apiClient.get<ApiResponse<AgentMessage[]>>(`/agents/messages/${sessionId}/agent/${agentId}`),

  getUnreadMessages: (agentId: string) =>
    apiClient.get<ApiResponse<AgentMessage[]>>(`/agents/messages/unread/${agentId}`),

  markAsRead: (agentId: string) =>
    apiClient.post<ApiResponse<{ agentId: string; markedAsRead: number }>>(`/agents/messages/read/${agentId}`),
};
