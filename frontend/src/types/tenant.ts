export interface Tenant {
  id: string;
  name: string;
  domainApp?: string; // CR-023: 소비앱 식별
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
  domainApp?: string; // CR-023
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

// CR-025: 시스템 API Key
export interface ApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  tenantId?: string;
  domainApp: string;
  scope?: Record<string, unknown>;
  lastUsedAt?: string;
  expiresAt?: string;
  isActive: boolean;
  createdBy?: string;
  createdAt: string;
}

export interface CreateApiKeyRequest {
  name: string;
  domainApp: string;
  tenantId?: string;
  scope?: Record<string, unknown>;
  expiresAt?: string;
}
