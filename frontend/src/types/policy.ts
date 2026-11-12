export interface PolicyRule {
  condition: string;
  action: string;
  actionType?: "allow" | "deny" | "require_approval" | "transform" | "rate_limit" | "log";
  params?: Record<string, unknown>;
}

export interface Policy {
  id: string;
  name: string;
  description?: string;
  priority?: number;
  enabled?: boolean;
  domain?: string;
  matchPattern?: string;
  match?: string;
  matchRules?: Record<string, unknown>;
  ruleCount?: number;
  triggeredCount?: number;
  rules?: PolicyRule[];
  createdAt?: string;
  updatedAt?: string;
}

export interface PolicyRequest {
  id: string;
  name: string;
  description?: string;
  priority?: number;
  enabled?: boolean;
  domain?: string;
  matchPattern?: string;
  matchRules?: Record<string, unknown>;
  rules?: Array<PolicyRule | Record<string, unknown>>;
}

export interface SimulateRequest {
  context: Record<string, unknown>;
}

export interface SimulateResult {
  result: "allow" | "deny" | "require_approval" | "transform";
  matchedRule?: string;
  reason?: string;
}
