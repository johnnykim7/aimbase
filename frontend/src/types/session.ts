export type SessionScopeType = "chat" | "workflow" | "project" | "runtime";

export type RuntimeKind = "claude_tool" | "llm_api" | "mcp_only";

export interface SessionMeta {
  id: string;
  sessionId: string;
  scopeType: SessionScopeType;
  runtimeKind?: RuntimeKind;
  workspaceRef?: string;
  persistentSession?: boolean;
  summaryVersion?: number;
  contextRecipeId?: string;
  lastToolChain?: string[];
  appId?: string;
  projectId?: string;
  parentSessionId?: string;
  title?: string;
  messageCount?: number;
  createdAt?: string;
  updatedAt?: string;
}
