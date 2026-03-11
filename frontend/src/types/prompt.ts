export interface Prompt {
  id: string;
  version?: string;
  name?: string;
  description?: string;
  domain?: string;
  content?: string;
  variables?: string[];
  abTestActive?: boolean;
  abTest?: ABTestResult;
  createdAt?: string;
  updatedAt?: string;
}

export interface ABTestResult {
  variantA?: { satisfaction: number; avgMs: number; count: number };
  variantB?: { satisfaction: number; avgMs: number; count: number };
  winner?: "A" | "B";
}

export interface PromptRequest {
  id: string;
  version?: string;
  name?: string;
  description?: string;
  domain?: string;
  content: string;
}

export interface PromptTestRequest {
  variables?: Record<string, string>;
  model?: string;
}

export interface PromptTestResult {
  output?: string;
  latencyMs?: number;
  tokenCount?: number;
}
