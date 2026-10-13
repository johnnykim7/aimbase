export interface WorkflowStep {
  id: string;
  name: string;
  type: "llm" | "tool" | "condition" | "parallel" | "approval" | "action" | "agent" | "evaluator_loop" | "sub_workflow" | string;
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
  domain?: string | null;
  projectId?: string | null;
  graphMode?: string | null;
  triggerConfig?: Record<string, unknown>;
  errorHandling?: Record<string, unknown> | null;
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

/** CR-149: 서버 저장 API 계약(T3-2). 서버는 이 밖의 칸을 400 으로 거부한다. */
export interface WorkflowRequest {
  id?: string;
  name: string;
  description?: string;
  domain?: string;
  projectId?: string;
  graphMode?: string;
  triggerConfig: Record<string, unknown>;
  steps: WorkflowStep[];
  errorHandling?: Record<string, unknown>;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
}

export type PlatformWorkflow = Workflow;

/** CR-102: 워크플로우 실행 이벤트 (workflow_run_events). API 응답은 snake_case. */
export type WorkflowRunEventType =
  | "STEP_START"
  | "TOOL_USE"
  | "TOOL_RESULT"
  | "LLM_REQUEST"
  | "LLM_RESPONSE"
  | "STEP_END"
  | "STEP_FAILED";

export interface WorkflowRunEvent {
  id: number;
  event_type: WorkflowRunEventType;
  step_id?: string | null;
  iteration?: number | null;
  tool_name?: string | null;
  duration_ms?: number | null;
  payload?: Record<string, unknown> | null;
  trace_id?: string | null;
  subagent_run_id?: string | null;
  created_at?: string | null;
  // 본문 전문 — include_body=true 또는 단건 조회 시에만 채워짐
  prompt_text?: string | null;
  response_text?: string | null;
  input_json?: Record<string, unknown> | null;
  output_text?: string | null;
}
