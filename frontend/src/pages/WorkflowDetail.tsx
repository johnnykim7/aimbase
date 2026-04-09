import { useState, useMemo, useEffect, lazy, Suspense } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { cn } from "@/lib/utils";
import { ActionButton } from "../components/common/ActionButton";
import { Badge } from "../components/common/Badge";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { useSetHeaderOverride } from "../components/layout/AppShell";
import { useWorkflows, useRunWorkflow } from "../hooks/useWorkflows";
import { workflowsApi } from "../api/workflows";
import type { Workflow, WorkflowStep, WorkflowRun } from "../types/workflow";

const WorkflowStudio = lazy(() => import("./WorkflowStudio"));

/* ------------------------------------------------------------------ */
/*  Step 시각화 상수                                                    */
/* ------------------------------------------------------------------ */

const STEP_TYPE_COLORS: Record<string, string> = {
  llm: "#2563eb",
  tool: "#7c3aed",
  condition: "#d97706",
  parallel: "#059669",
  approval: "#dc2626",
  action: "#93c5fd",
};

const STEP_TYPE_ICONS: Record<string, string> = {
  llm: "🤖",
  tool: "🔧",
  condition: "⑂",
  parallel: "⚡",
  approval: "✋",
  action: "▶",
};

/* ------------------------------------------------------------------ */
/*  RunDetailPanel — 실행 상세 (단계별 카드 클릭 → 상세 펼침)            */
/* ------------------------------------------------------------------ */

function RunDetailPanel({ selectedRun }: { selectedRun: WorkflowRun | null }) {
  const [expandedStep, setExpandedStep] = useState<string | null>(null);

  if (!selectedRun) {
    return (
      <div className="flex-1 flex items-center justify-center bg-card border border-border rounded-xl min-h-[200px]">
        <span className="text-muted-foreground/40 text-[13px]">왼쪽에서 실행 이력을 선택하세요</span>
      </div>
    );
  }

  const stepEntries = selectedRun.stepResults
    ? Object.entries(selectedRun.stepResults).sort((a, b) => {
        const tA = a[1]._startedAt as string | undefined;
        const tB = b[1]._startedAt as string | undefined;
        if (tA && tB) return tA.localeCompare(tB);
        return 0;
      })
    : [];
  const totalMs = selectedRun.startedAt && selectedRun.completedAt
    ? new Date(selectedRun.completedAt).getTime() - new Date(selectedRun.startedAt).getTime()
    : null;

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* 헤더 — 고정 */}
      <div className="bg-card border border-border rounded-t-xl px-5 py-4 shrink-0">
        <div className="flex justify-between items-center mb-2">
          <div className="text-[13px] font-semibold text-foreground">실행 상세</div>
          <Badge color={selectedRun.status === "completed" ? "success" : selectedRun.status === "failed" ? "danger" : "warning"}>
            {selectedRun.status}
          </Badge>
        </div>
        <div className="flex gap-4 flex-wrap text-[11px] font-mono text-muted-foreground">
          <span>ID: {selectedRun.id.slice(0, 8)}…</span>
          <span>시작: {selectedRun.startedAt ? new Date(selectedRun.startedAt).toLocaleString("ko-KR") : "—"}</span>
          {selectedRun.completedAt && <span>완료: {new Date(selectedRun.completedAt).toLocaleString("ko-KR")}</span>}
          {totalMs != null && (
            <span className="text-primary font-semibold">총 {(totalMs / 1000).toFixed(1)}초</span>
          )}
        </div>
        {selectedRun.error && (
          <div className="mt-2 text-[11px] font-mono text-destructive py-1.5 px-2.5 bg-destructive/10 rounded-md">
            {typeof selectedRun.error === "string" ? selectedRun.error : JSON.stringify(selectedRun.error)}
          </div>
        )}
      </div>

      {/* 단계별 카드 — 스크롤 영역 */}
      <div className="flex-1 overflow-auto bg-card border border-border border-t-0 rounded-b-xl px-5 py-3 pb-5">
        {stepEntries.length === 0 ? (
          <div className="text-center p-5 text-muted-foreground/40 text-xs">단계 결과 없음</div>
        ) : (
          <div className="flex flex-col gap-2">
            {stepEntries.map(([stepId, result], idx) => {
              const dur = result._durationMs as number | undefined;
              const hasError = !!result.error;
              const isExpanded = expandedStep === stepId;
              const borderColor = hasError ? "#dc2626" : "#059669";

              // 상세 표시용 데이터 (메타 필드 제외)
              const detailData: Record<string, unknown> = {};
              for (const [k, v] of Object.entries(result)) {
                if (!k.startsWith("_")) detailData[k] = v;
              }

              return (
                <div key={stepId}>
                  <div
                    onClick={() => setExpandedStep(isExpanded ? null : stepId)}
                    className="flex items-center justify-between cursor-pointer transition-all"
                    style={{
                      padding: "10px 14px",
                      borderRadius: isExpanded ? "8px 8px 0 0" : 8,
                      background: "hsl(var(--background))",
                      border: `1px solid ${isExpanded ? borderColor : "hsl(var(--border))"}`,
                      borderBottom: isExpanded ? "none" : undefined,
                    }}
                  >
                    <div className="flex items-center gap-2.5">
                      <span className="w-[7px] h-[7px] rounded-full shrink-0" style={{ background: borderColor }} />
                      <span className="text-xs font-semibold text-foreground">
                        {idx + 1}. {stepId}
                      </span>
                    </div>
                    <div className="flex items-center gap-2.5">
                      {dur != null && (
                        <span className={cn(
                          "font-mono text-[11px] font-semibold",
                          dur > 10000 ? "text-destructive" : dur > 3000 ? "text-warning" : "text-success"
                        )}>
                          {dur >= 1000 ? `${(dur / 1000).toFixed(1)}s` : `${dur}ms`}
                        </span>
                      )}
                      <span className={cn(
                        "text-[10px] text-muted-foreground/40 transition-transform",
                        isExpanded && "rotate-180"
                      )}>▼</span>
                    </div>
                  </div>

                  {isExpanded && (
                    <div
                      className="font-mono text-[11px] text-foreground leading-relaxed whitespace-pre-wrap break-all"
                      style={{
                        padding: 14,
                        borderRadius: "0 0 8px 8px",
                        border: `1px solid ${borderColor}`,
                        borderTop: "1px dashed hsl(var(--border))",
                        background: "hsl(var(--background))",
                      }}
                    >
                      {JSON.stringify(detailData, null, 2)}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  FlowPreview                                                        */
/* ------------------------------------------------------------------ */

interface FlowPreviewProps {
  steps: WorkflowStep[];
  runDetail?: Record<string, unknown> | null;
}

type StepStatus = "waiting" | "running" | "completed" | "failed";

const STATUS_STYLES: Record<StepStatus, { border: string; bg: string; icon: string }> = {
  waiting: { border: "hsl(var(--border))", bg: "transparent", icon: "" },
  running: { border: "#2563eb", bg: "rgba(37,99,235,0.09)", icon: "⏳ " },
  completed: { border: "#059669", bg: "rgba(5,150,105,0.09)", icon: "✓ " },
  failed: { border: "#dc2626", bg: "rgba(220,38,38,0.09)", icon: "✗ " },
};

function getStepStatus(step: WorkflowStep, runDetail?: Record<string, unknown> | null): StepStatus {
  if (!runDetail) return "waiting";
  const currentStep = runDetail.currentStep as string | undefined;
  const stepResults = runDetail.stepResults as Record<string, Record<string, unknown>> | undefined;
  const overallStatus = runDetail.status as string | undefined;

  const result = stepResults?.[step.id] ?? stepResults?.[step.name];

  if (result) {
    if (result.error) return "failed";
    return "completed";
  }

  if (currentStep === step.id || currentStep === step.name) return "running";

  if (overallStatus === "failed") {
    return "waiting";
  }

  return "waiting";
}

function FlowPreview({ steps, runDetail }: FlowPreviewProps) {
  return (
    <div
      className="rounded-lg p-5 min-h-[200px]"
      style={{
        background: "radial-gradient(circle, hsl(var(--border) / 0.19) 1px, transparent 1px)",
        backgroundSize: "20px 20px",
      }}
    >
      <div className="flex flex-col items-center gap-0">
        {steps.map((step, i) => {
          const typeColor = STEP_TYPE_COLORS[step.type] ?? "#9ca3af";
          const status = getStepStatus(step, runDetail);
          const ss = STATUS_STYLES[status];
          const borderColor = status === "waiting" ? typeColor : ss.border;
          const bgColor = status === "waiting" ? typeColor + "15" : ss.bg;

          const stepResults = runDetail?.stepResults as Record<string, Record<string, unknown>> | undefined;
          const stepResult = stepResults?.[step.id] ?? stepResults?.[step.name];
          const durationMs = stepResult?._durationMs as number | undefined;

          return (
            <div key={step.id} className="flex flex-col items-center">
              <div
                className={cn(
                  "flex items-center gap-1.5 min-w-[160px] justify-center text-xs font-semibold text-foreground transition-all duration-300",
                  status === "running" && "animate-pulse"
                )}
                style={{
                  padding: "10px 20px",
                  borderRadius: 10,
                  border: `2px solid ${borderColor}`,
                  background: bgColor,
                }}
              >
                {ss.icon && <span className="text-[11px]">{ss.icon}</span>}
                <span>{STEP_TYPE_ICONS[step.type] ?? "▶"}</span>
                {step.name}
                {durationMs != null && (
                  <span className="text-[10px] font-mono text-muted-foreground font-normal">
                    {durationMs >= 1000 ? `${(durationMs / 1000).toFixed(1)}s` : `${durationMs}ms`}
                  </span>
                )}
              </div>
              {i < steps.length - 1 && (
                <div className="w-0.5 h-5 relative transition-colors duration-300" style={{ background: borderColor }}>
                  <div
                    className="absolute -bottom-1 -left-[3px] w-2 h-2"
                    style={{
                      borderRight: `2px solid ${borderColor}`,
                      borderBottom: `2px solid ${borderColor}`,
                      transform: "rotate(45deg)",
                    }}
                  />
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  입력 필드 해석                                                      */
/* ------------------------------------------------------------------ */

interface InputFieldMeta {
  name: string;
  description?: string;
  required?: boolean;
}

function resolveInputFields(wf: Workflow): InputFieldMeta[] {
  const schema = wf.inputSchema as Record<string, unknown> | undefined;
  if (schema?.properties && typeof schema.properties === "object") {
    const props = schema.properties as Record<string, Record<string, unknown>>;
    const requiredList = Array.isArray(schema.required) ? (schema.required as string[]) : [];
    return Object.entries(props).map(([key, prop]) => ({
      name: key,
      description: (prop.description as string) ?? undefined,
      required: requiredList.includes(key),
    }));
  }
  const pattern = /\{\{input\.(\w+)\}\}/g;
  const fields = new Set<string>();
  const stepsJson = JSON.stringify(wf.steps ?? []);
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(stepsJson)) !== null) {
    fields.add(match[1]);
  }
  return Array.from(fields).map((f) => ({ name: f }));
}

/* ------------------------------------------------------------------ */
/*  StepDetailPanel — 단계 config 상세 (response_schema 포함)            */
/* ------------------------------------------------------------------ */

function StepDetailPanel({ step }: { step: WorkflowStep }) {
  const config = step.config ?? {};
  const responseSchema = config.response_schema as Record<string, unknown> | undefined;
  const systemPrompt = config.system as string | undefined;
  const prompt = config.prompt as string | undefined;
  const connectionId = config.connection_id as string | undefined;
  const toolName = config.tool as string | undefined;
  const toolInput = config.input as Record<string, unknown> | undefined;

  const excluded = ["response_schema", "system", "prompt", "connection_id", "tool", "input", "model"];
  const extraKeys = Object.keys(config).filter((k) => !excluded.includes(k));

  return (
    <div className="px-3 py-1 pb-2">
      {connectionId && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground uppercase tracking-wider mb-1 mt-2.5">Connection ID</div>
          <div className="text-[11px] font-mono text-muted-foreground/40">{connectionId}</div>
        </div>
      )}
      {toolName && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground uppercase tracking-wider mb-1 mt-2.5">Tool</div>
          <div className="text-[11px] font-mono text-muted-foreground/40">{toolName}</div>
          {toolInput && (
            <>
              <div className="text-[10px] font-mono text-muted-foreground uppercase tracking-wider mb-1 mt-1.5">Tool Input</div>
              <div className="p-2.5 rounded-md bg-background border border-border text-[11px] font-mono text-foreground leading-normal max-h-60 overflow-auto whitespace-pre-wrap break-words">
                {JSON.stringify(toolInput, null, 2)}
              </div>
            </>
          )}
        </div>
      )}
      {systemPrompt && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground uppercase tracking-wider mb-1 mt-2.5">System Prompt</div>
          <div className="p-2.5 rounded-md bg-background border border-border text-[11px] font-mono text-muted-foreground/40 leading-normal max-h-[120px] overflow-auto whitespace-pre-wrap break-words">
            {systemPrompt}
          </div>
        </div>
      )}
      {prompt && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground uppercase tracking-wider mb-1 mt-2.5">Prompt Template</div>
          <div className="p-2.5 rounded-md bg-background border border-border text-[11px] font-mono text-muted-foreground/40 leading-normal max-h-[120px] overflow-auto whitespace-pre-wrap break-words">
            {prompt}
          </div>
        </div>
      )}
      {responseSchema && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground uppercase tracking-wider mb-1 mt-2.5 flex items-center gap-1.5">
            <span className="text-primary">◆</span> Response Schema
          </div>
          <div className="p-2.5 rounded-md bg-background border border-border text-[11px] font-mono text-foreground leading-normal max-h-60 overflow-auto whitespace-pre-wrap break-words">
            {JSON.stringify(responseSchema, null, 2)}
          </div>
        </div>
      )}
      {extraKeys.length > 0 && (
        <div>
          <div className="text-[10px] font-mono text-muted-foreground uppercase tracking-wider mb-1 mt-2.5">기타 Config</div>
          <div className="p-2.5 rounded-md bg-background border border-border text-[11px] font-mono text-foreground leading-normal max-h-60 overflow-auto whitespace-pre-wrap break-words">
            {JSON.stringify(Object.fromEntries(Object.entries(config).filter(([k]) => !excluded.includes(k))), null, 2)}
          </div>
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Main Component                                                     */
/* ------------------------------------------------------------------ */

type Tab = "test" | "history" | "edit";

export default function WorkflowDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: workflows = [], isLoading } = useWorkflows();
  const runWorkflow = useRunWorkflow();
  const setHeaderOverride = useSetHeaderOverride();

  const workflow = useMemo(
    () => (workflows as Workflow[]).find((w) => w.id === id),
    [id, workflows],
  );

  useEffect(() => {
    if (workflow) {
      setHeaderOverride({ title: workflow.name, subtitle: workflow.description || "워크플로우 상세" });
    }
    return () => setHeaderOverride(null);
  }, [workflow?.name, workflow?.description]);

  const [activeTab, setActiveTab] = useState<Tab>("test");
  const [expandedStepId, setExpandedStepId] = useState<string | null>(null);
  const [lastRunDetail, setLastRunDetail] = useState<Record<string, unknown> | null>(null);
  const [runStatus, setRunStatus] = useState<string | null>(null);

  // 실행 이력
  const [runs, setRuns] = useState<WorkflowRun[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);
  const [selectedRun, setSelectedRun] = useState<WorkflowRun | null>(null);

  const loadRuns = async () => {
    if (!id) return;
    setRunsLoading(true);
    try {
      const res = await workflowsApi.runs(id);
      const data = res.data.data;
      const list = Array.isArray(data) ? data : (data as { content: WorkflowRun[] })?.content ?? [];
      setRuns(list);
    } catch { /* ignore */ }
    setRunsLoading(false);
  };

  useEffect(() => {
    if (activeTab === "history") loadRuns();
  }, [activeTab, id]);

  // 입력 다이얼로그
  const [inputValues, setInputValues] = useState<Record<string, string>>({});
  const fieldMetas = useMemo(() => (workflow ? resolveInputFields(workflow) : []), [workflow]);

  // 초기화
  const resetInputs = () => {
    const vals: Record<string, string> = {};
    fieldMetas.forEach((f) => (vals[f.name] = ""));
    setInputValues(vals);
  };

  const handleRun = async () => {
    if (!workflow) return;
    const missing = fieldMetas.filter((f) => f.required && !inputValues[f.name]?.trim());
    if (missing.length > 0) {
      alert(`필수 입력: ${missing.map((f) => f.name).join(", ")}`);
      return;
    }
    const input: Record<string, unknown> = {};
    fieldMetas.forEach((f) => {
      if (inputValues[f.name]) input[f.name] = inputValues[f.name];
    });

    setRunStatus("실행 중...");
    setLastRunDetail(null);
    try {
      const result = await runWorkflow.mutateAsync({
        id: workflow.id,
        input: Object.keys(input).length > 0 ? input : undefined,
      });
      const status = result?.status ?? "started";
      setRunStatus(status);
      setLastRunDetail(result as unknown as Record<string, unknown>);
      if (status === "running") {
        const runId = (result as { id?: string })?.id;
        if (runId) {
          const poll = async (attempt: number) => {
            if (attempt >= 120) return;
            try {
              const updated = await workflowsApi.getRun(workflow.id, runId);
              const run = updated.data.data;
              setRunStatus(run?.status ?? status);
              setLastRunDetail(run as unknown as Record<string, unknown>);
              if (run?.status !== "running") return;
            } catch (err) {
              console.warn(`[워크플로우 폴링] attempt=${attempt} 에러:`, err);
            }
            const interval = attempt < 5 ? 1000 : 3000;
            setTimeout(() => poll(attempt + 1), interval);
          };
          setTimeout(() => poll(0), 1000);
        }
      }
    } catch (e) {
      setRunStatus("오류 발생");
      setLastRunDetail({ error: String(e) });
    }
  };

  if (isLoading) return <LoadingSpinner fullPage />;
  if (!workflow) {
    return (
      <div className="p-10 text-center text-muted-foreground">
        워크플로우를 찾을 수 없습니다.
        <div className="mt-3">
          <ActionButton variant="ghost" onClick={() => navigate("/workflows")}>
            목록으로
          </ActionButton>
        </div>
      </div>
    );
  }

  const isEdit = activeTab === "edit";

  return (
    <Page
      actions={
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate("/workflows")}
            className="bg-transparent border-none cursor-pointer text-sm text-muted-foreground py-1 px-2.5 rounded-md hover:bg-accent"
          >
            ← 목록
          </button>
          <Badge color={workflow.status === "active" ? "success" : workflow.status === "inactive" ? "muted" : "warning"}>
            {workflow.status ?? "draft"}
          </Badge>
        </div>
      }
      noPadding
    >
      {/* 탭 바 */}
      <div className="flex border-b border-border px-8 shrink-0">
        {(["test", "history", "edit"] as const).map((t) => (
          <button
            key={t}
            onClick={() => setActiveTab(t)}
            className={cn(
              "py-2.5 px-6 text-[13px] bg-transparent border-none cursor-pointer transition-colors -mb-px",
              activeTab === t
                ? "font-semibold text-primary border-b-2 border-b-primary"
                : "font-normal text-muted-foreground border-b-2 border-b-transparent hover:text-foreground"
            )}
          >
            {t === "test" ? "테스트 실행" : t === "history" ? "실행 이력" : "편집"}
          </button>
        ))}
      </div>

      {activeTab === "test" && (
        <div className="flex-1 overflow-auto p-5 px-8 pb-8 flex gap-5">
          {/* 왼쪽: 플로우 미리보기 */}
          <div className="flex-1 bg-card border border-border rounded-xl p-5">
            <div className="text-xs font-mono text-muted-foreground uppercase tracking-wider mb-3">
              워크플로우 구조
            </div>
            {(workflow.steps ?? []).length > 0 ? (
              <FlowPreview steps={workflow.steps!} runDetail={lastRunDetail} />
            ) : (
              <div className="text-center p-8 text-muted-foreground/40 font-mono text-xs">
                단계 정보 없음
              </div>
            )}

            {/* 스텝 상세 */}
            {(workflow.steps ?? []).length > 0 && (
              <div className="mt-4">
                <div className="text-xs font-mono text-muted-foreground uppercase tracking-wider mb-2">
                  단계 목록
                </div>
                <div className="flex flex-col gap-1.5">
                  {workflow.steps!.map((step, i) => {
                    const isExpanded = expandedStepId === step.id;
                    const hasSchema = !!(step.config as Record<string, unknown> | undefined)?.response_schema;
                    return (
                      <div key={step.id}>
                        <div
                          onClick={() => setExpandedStepId(isExpanded ? null : step.id)}
                          className={cn(
                            "flex items-center gap-2 text-xs cursor-pointer transition-all py-2 px-3",
                            isExpanded
                              ? "bg-primary/5 border border-primary/25 rounded-t-lg"
                              : "bg-background border border-border rounded-lg hover:bg-accent"
                          )}
                        >
                          <span className="text-muted-foreground/40 font-mono text-[10px] min-w-[20px]">
                            {i + 1}
                          </span>
                          <span>{STEP_TYPE_ICONS[step.type] ?? "▶"}</span>
                          <span className="font-semibold text-foreground">{step.name}</span>
                          <Badge color="muted">{step.type}</Badge>
                          {hasSchema && <span className="text-[10px] text-primary ml-auto" title="Response Schema 정의됨">◆ schema</span>}
                          <span className={cn("text-[10px] text-muted-foreground/40", !hasSchema && "ml-auto")}>{isExpanded ? "▲" : "▼"}</span>
                        </div>
                        {isExpanded && (
                          <div className="rounded-b-lg border border-primary/25 border-t-0 bg-background">
                            <StepDetailPanel step={step} />
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>

          {/* 오른쪽: 테스트 실행 패널 */}
          <div className="flex-1 bg-card border border-border rounded-xl p-5 flex flex-col gap-4 self-start">
            <div className="text-[13px] font-semibold text-foreground">
              테스트 실행
            </div>

            {fieldMetas.length > 0 ? (
              <div className="flex flex-col gap-3">
                {fieldMetas.map((meta) => (
                  <div key={meta.name}>
                    <div className="text-[11px] font-mono text-muted-foreground mb-1">
                      {meta.name}
                      {meta.required && <span className="text-destructive ml-1">*</span>}
                    </div>
                    {meta.description && (
                      <div className="text-[11px] text-muted-foreground/40 mb-1">
                        {meta.description}
                      </div>
                    )}
                    <textarea
                      value={inputValues[meta.name] ?? ""}
                      onChange={(e) => setInputValues((v) => ({ ...v, [meta.name]: e.target.value }))}
                      rows={3}
                      className="w-full py-2 px-2.5 rounded-lg border border-border bg-background text-xs font-mono text-foreground resize-y box-border"
                      placeholder={meta.description ?? `{{input.${meta.name}}}`}
                    />
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-xs text-muted-foreground">
                입력 파라미터 없음 — 바로 실행 가능
              </div>
            )}

            <div className="flex gap-2">
              <ActionButton
                variant="primary"
                onClick={handleRun}
                disabled={runWorkflow.isPending}
              >
                {runStatus === "실행 중..." ? "실행 중..." : "실행"}
              </ActionButton>
              {fieldMetas.length > 0 && (
                <ActionButton variant="ghost" onClick={resetInputs}>
                  초기화
                </ActionButton>
              )}
            </div>

            {runStatus && (
              <div>
                <div className="text-[11px] font-mono text-muted-foreground mb-1.5">
                  상태: <Badge color={runStatus === "completed" ? "success" : runStatus === "오류 발생" ? "danger" : "warning"}>{runStatus}</Badge>
                </div>
              </div>
            )}

            {lastRunDetail && (
              <div>
                <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-1.5">
                  실행 결과
                </div>
                <div className="p-3 rounded-lg bg-background border border-border text-xs font-mono text-foreground leading-relaxed max-h-[400px] overflow-auto whitespace-pre-wrap">
                  {JSON.stringify(lastRunDetail, null, 2)}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 실행 이력 탭 */}
      {activeTab === "history" && (
        <div className="flex-1 overflow-hidden p-5 px-8 pb-8 flex gap-5">
          {/* 왼쪽: 이력 목록 */}
          <div className="flex-1 flex flex-col min-h-0">
            <div className="flex justify-between items-center mb-2 shrink-0">
              <div className="text-xs font-mono text-muted-foreground uppercase tracking-wider">
                실행 이력
              </div>
              <ActionButton small variant="ghost" onClick={loadRuns}>
                새로고침
              </ActionButton>
            </div>

            <div className="flex-1 overflow-auto flex flex-col gap-2">
            {runsLoading && <LoadingSpinner />}

            {!runsLoading && runs.length === 0 && (
              <div className="text-center p-10 text-muted-foreground/40 text-[13px]">
                실행 이력이 없습니다
              </div>
            )}

            {runs.map((run) => {
              const isSelected = selectedRun?.id === run.id;
              const dotColor = run.status === "completed" ? "#059669"
                : run.status === "failed" ? "#dc2626"
                : run.status === "running" ? "#2563eb"
                : "#d97706";
              return (
                <div
                  key={run.id}
                  onClick={() => setSelectedRun(run)}
                  className={cn(
                    "py-3 px-4 rounded-xl border cursor-pointer transition-all flex justify-between items-center",
                    isSelected
                      ? "border-primary bg-primary/5"
                      : "border-border bg-card hover:bg-accent"
                  )}
                >
                  <div className="flex flex-col gap-1">
                    <div className="flex items-center gap-2">
                      <Badge color={run.status === "completed" ? "success" : run.status === "failed" ? "danger" : "warning"}>
                        {run.status}
                      </Badge>
                      <span className="text-[11px] font-mono text-muted-foreground/40">
                        {run.id.slice(0, 8)}…
                      </span>
                    </div>
                    <div className="text-[11px] font-mono text-muted-foreground">
                      {run.startedAt ? new Date(run.startedAt).toLocaleString("ko-KR") : "—"}
                      {run.completedAt && (
                        <span className="ml-2 text-muted-foreground/40">
                          → {new Date(run.completedAt).toLocaleString("ko-KR")}
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="w-2 h-2 rounded-full shrink-0" style={{ background: dotColor }} />
                </div>
              );
            })}
            </div>
          </div>

          {/* 오른쪽: 선택된 실행 상세 */}
          <RunDetailPanel selectedRun={selectedRun} />
        </div>
      )}

      {/* 편집 탭 — 스튜디오 임베드 */}
      {isEdit && (
        <div className="flex-1 overflow-hidden">
          <Suspense fallback={<LoadingSpinner fullPage />}>
            <WorkflowStudio embedded />
          </Suspense>
        </div>
      )}
    </Page>
  );
}
