export type ContextSourceType =
  | "system_policy"
  | "session_summary"
  | "recent_conversation"
  | "tool_contract"
  | "rag_knowledge"
  | "project_context"
  | "domain_prompt"
  | "user_preference";

export interface ContextSourceConfig {
  source: ContextSourceType;
  enabled: boolean;
  maxTokens?: number;
  priority?: number;
  evictable?: boolean;
  freshnessMinutes?: number;
  config?: Record<string, unknown>;
}

export interface ContextRecipe {
  id: string;
  name: string;
  description?: string;
  domainApp?: string;
  scopeType?: string;
  sources?: ContextSourceConfig[];
  priority?: number;
  active?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface ContextRecipeRequest {
  name: string;
  description?: string;
  domainApp?: string;
  scopeType?: string;
  sources?: ContextSourceConfig[];
  priority?: number;
  active?: boolean;
}

export interface AssemblyTrace {
  recipeId: string;
  resolveReason?: string;
  includedLayers?: string[];
  evictedLayers?: string[];
  staleLayers?: string[];
  totalEstimatedTokens?: number;
  effectiveWindow?: number;
  assemblyDurationMs?: number;
}
