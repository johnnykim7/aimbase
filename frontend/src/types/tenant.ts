export interface Tenant {
  id: string;
  name: string;
  dbHost?: string;
  dbPort?: number;
  dbName?: string;
  dbUsername?: string;
  status: "active" | "suspended" | "terminated";
  plan?: "free" | "standard" | "enterprise";
  createdAt?: string;
  updatedAt?: string;
}

export interface TenantRequest {
  id: string;
  name: string;
  adminEmail?: string;
  plan?: string;
  dbHost?: string;
  dbName?: string;
}

export interface Subscription {
  id?: string;
  tenantId: string;
  tenantName?: string;
  plan?: string;
  monthlyTokenQuota?: number;
  dailyRequestQuota?: number;
  maxConnections?: number;
  maxWorkflows?: number;
  expiresAt?: string;
  usedTokens?: number;
  usedRequests?: number;
}

export interface PlatformUsage {
  activeTenants?: number;
  totalRequestsToday?: number;
  totalCostTodayUsd?: number;
  avgLatencyMs?: number;
  tenantUsages?: TenantUsage[];
}

export interface TenantUsage {
  tenantId: string;
  tenantName?: string;
  requestCount?: number;
  tokenCount?: number;
  costUsd?: number;
  status?: string;
}
