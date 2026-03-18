export interface DashboardStats {
  cost_today_usd: number;
  tokens_today: number;
  pending_approvals: number;
  active_connections: number;
}

export interface ActionLog {
  id: string;
  sessionId?: string;
  actionType?: string;
  adapterId?: string;
  status: "success" | "failure" | "pending" | "warning";
  durationMs?: number;
  message?: string;
  actions?: string;
  metadata?: Record<string, unknown>;
  executedAt?: string;
  createdAt?: string;
}

export interface AuditLog {
  id: string;
  userId?: string;
  action: string;
  resource?: string;
  detail?: string;
  createdAt: string;
}

export interface UsageStat {
  modelId?: string;
  modelName?: string;
  requestCount?: number;
  tokenCount?: number;
  costUsd?: number;
  avgLatencyMs?: number;
  successRate?: number;
  date?: string;
}

export interface Approval {
  id: string;
  workflowRunId?: string;
  requestedBy?: string;
  approver?: string;
  amount?: number;
  currency?: string;
  status: "pending" | "approved" | "rejected" | "expired";
  requestedAt?: string;
  context?: Record<string, unknown>;
  description?: string;
}

export interface CostBreakdown {
  model: string;
  inputCost: number;
  outputCost: number;
  totalCost: number;
}

export interface CostTrendPoint {
  date: string;
  model: string;
  cost: number;
}
