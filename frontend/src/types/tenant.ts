export interface App {
  id: string;
  name: string;
  description?: string;
  status: "active" | "suspended" | "deleted";
  dbName?: string;
  ownerEmail?: string;
  maxTenants?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface AppCreateRequest {
  appId: string;
  name: string;
  description?: string;
  ownerEmail: string;
  ownerPassword: string;
  maxTenants?: number;
}

export interface AppUpdateRequest {
  name?: string;
  description?: string;
  ownerEmail?: string;
  maxTenants?: number;
}

export interface Tenant {
  id: string;
  appId?: string;
  name: string;
  dbHost?: string;
  dbPort?: number;
  dbName?: string;
  dbUsername?: string;
  adminEmail?: string;
  status: "active" | "suspended" | "terminated";
  plan?: "free" | "standard" | "enterprise";
  createdAt?: string;
  updatedAt?: string;
}

export interface TenantRequest {
  id: string;
  appId?: string;
  name: string;
  adminEmail?: string;
  plan?: string;
  dbHost?: string;
  dbName?: string;
}

export interface AppTenantCreateRequest {
  tenantId: string;
  name: string;
  adminEmail: string;
  initialAdminPassword: string;
  plan?: string;
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
