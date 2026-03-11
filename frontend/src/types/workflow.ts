export interface WorkflowStep {
  id: string;
  name: string;
  type: "llm" | "tool" | "condition" | "parallel" | "approval" | "action";
  config?: Record<string, unknown>;
  nextSteps?: string[];
  conditionBranches?: { condition: string; nextStep: string }[];
}

export interface Workflow {
  id: string;
  name: string;
  description?: string;
  trigger?: string;
  steps?: WorkflowStep[];
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
  error?: string;
  steps?: { stepId: string; status: string; output?: unknown }[];
}

export interface WorkflowRequest {
  id: string;
  name: string;
  description?: string;
  trigger?: string;
  steps?: WorkflowStep[];
  status?: string;
}
