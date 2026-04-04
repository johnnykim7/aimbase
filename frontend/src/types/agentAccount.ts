export interface AccountPoolStatus {
  accountId: string;
  name: string;
  agentType: string;
  status: string;
  healthStatus: string;
  circuitState: string;
  currentConcurrency: number;
  maxConcurrent: number;
  lastHealthAt?: string;
}

export interface AgentAccount {
  id: string;
  name: string;
  agentType: string;
  authType: string;
  containerHost: string;
  containerPort: number;
  status: string;
  priority: number;
  maxConcurrent: number;
  config: Record<string, unknown>;
  healthStatus: string;
  lastHealthAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface AgentAccountCreateRequest {
  id: string;
  name: string;
  agentType: string;
  authType: string;
  containerHost: string;
  containerPort: number;
  status?: string;
  priority?: number;
  maxConcurrent?: number;
  config?: Record<string, unknown>;
}

export interface AgentAccountAssignment {
  id: number;
  account: AgentAccount;
  tenantId?: string;
  appId?: string;
  assignmentType: string;
  priority: number;
  isActive: boolean;
  createdAt?: string;
}

export interface AssignmentCreateRequest {
  account_id: string;
  tenant_id?: string;
  app_id?: string;
  assignment_type?: string;
  priority?: number;
}

export interface GuideEntry {
  slug: string;
  title: string;
  filename: string;
}

export interface GuideDetail {
  slug: string;
  title: string;
  content: string;
}
