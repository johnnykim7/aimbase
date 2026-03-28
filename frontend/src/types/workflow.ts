export interface WorkflowStep {
  id: string;
  name: string;
  type: "llm" | "tool" | "condition" | "parallel" | "approval" | "action" | string;
  config?: Record<string, unknown>;
  dependsOn?: string[];
  nextSteps?: string[];
  onSuccess?: string;
  onFailure?: string;
  timeoutMs?: number;
  conditionBranches?: { condition: string; nextStep: string }[];
}

export interface Workflow {
  id: string;
  name: string;
  description?: string;
  trigger?: string;
  steps?: WorkflowStep[];
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  stepCount?: number;
  runCount?: number;
  successRate?: number;
  status?: "active" | "inactive" | "draft";
  createdAt?: string;
  updatedAt?: string;
}

export interface WorkflowRun {
  id: string;
  workflowId: string;
  status: "running" | "completed" | "failed" | "pending_approval";
  startedAt?: string;
  completedAt?: string;
  error?: Record<string, unknown> | string;
  stepResults?: Record<string, Record<string, unknown>>;
  currentStep?: string;
  inputData?: Record<string, unknown>;
  steps?: { stepId: string; status: string; output?: unknown }[];
}

export interface WorkflowRequest {
  id: string;
  name: string;
  description?: string;
  trigger?: string;
  steps?: WorkflowStep[];
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  status?: string;
}
