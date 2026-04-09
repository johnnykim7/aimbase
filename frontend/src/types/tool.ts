export type PermissionLevel = "read_only" | "cautious" | "standard" | "autonomous";

export type ToolScope = "session" | "project" | "global";

export type ApprovalState = "approved" | "pending" | "denied" | "not_required";

export interface ToolContract {
  id: string;
  name: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  permissionLevel: PermissionLevel;
  approvalRequired: boolean;
  readOnly: boolean;
  destructive: boolean;
  concurrencySafe: boolean;
  tags?: string[];
  capabilities?: string[];
}

export interface ToolContext {
  tenantId?: string;
  appId?: string;
  projectId?: string;
  sessionId?: string;
  workflowRunId?: string;
  permissionLevel?: PermissionLevel;
  approvalState?: ApprovalState;
  workspacePath?: string;
  dryRun?: boolean;
}

export interface ToolArtifact {
  type: string;
  ref?: string;
  data?: Record<string, unknown>;
}

export interface ToolResult {
  success: boolean;
  output?: unknown;
  summary?: string;
  artifacts?: ToolArtifact[];
  sideEffects?: string[];
  auditPayload?: Record<string, unknown>;
  nextContextHint?: string;
  durationMs?: number;
}

export interface ToolExecution {
  id: string;
  sessionId: string;
  workflowRunId?: string;
  stepId?: string;
  turnNumber?: number;
  sequenceInTurn?: number;
  toolId: string;
  toolName: string;
  inputSummary?: string;
  outputSummary?: string;
  success: boolean;
  durationMs?: number;
  runtimeKind?: string;
  createdAt?: string;
}

export interface WorkspacePolicy {
  allowedRoots?: string[];
  deniedPaths?: string[];
  allowedExtensions?: string[];
  deniedExtensions?: string[];
  maxFileSize?: number;
  denyBinary?: boolean;
}
