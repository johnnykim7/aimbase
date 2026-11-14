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
  domainApp?: string; // CR-023: 소비앱 식별
  dbHost?: string;
  dbPort?: number;
  dbName?: string;
  dbUsername?: string;
  adminEmail?: string;
  status: "active" | "suspended" | "terminated";
  createdAt?: string;
  updatedAt?: string;
}

export interface TenantRequest {
  id: string;
  appId?: string;
  name: string;
  adminEmail?: string;
  plan?: string;
  domainApp?: string; // CR-023
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

// CR-191: 플랜 목록과 플랜별 분당 요청 한도. 이름과 값은 운영 설정(plan.*)에서 온다.
export interface PlanInfo {
  code: string;
  apiRpmLimit: number;
}

export interface PlanCatalog {
  defaultPlan: string;
  plans: PlanInfo[];
}

export interface Subscription {
  tenantId: string;
  tenantName?: string;
  plan?: string;
  llmMonthlyTokenQuota: number;
  apiRpmLimit: number;
  maxConnections: number;
  maxWorkflows?: number;
  validUntil?: string | null;
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
