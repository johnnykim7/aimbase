export interface DomainAppConfig {
  id: string;
  domainApp: string;
  defaultContextRecipeId?: string;
  defaultToolAllowlist?: string[];
  defaultToolDenylist?: string[];
  defaultPolicyPreset?: string;
  defaultSessionScope?: string;
  defaultRuntime?: string;
  mcpServerIds?: string[];
  config?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}
