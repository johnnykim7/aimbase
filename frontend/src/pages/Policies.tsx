import { useState } from "react";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { Shield } from "lucide-react";
import {
  usePolicies,
  useCreatePolicy,
  useDeletePolicy,
  useSimulatePolicy,
} from "../hooks/usePolicies";
import type { Policy, PolicyRule } from "../types/policy";

const RULE_TYPE_OPTIONS = [
  { value: "content_filter", label: "콘텐츠 필터", description: "금지 키워드/패턴 차단", fields: ["keywords", "action"] },
  { value: "cost_limit", label: "비용 제한", description: "일/월 비용 한도 설정", fields: ["max_cost", "period"] },
  { value: "token_limit", label: "토큰 제한", description: "요청당 최대 토큰 수 제한", fields: ["max_tokens"] },
  { value: "rate_limit", label: "호출 제한", description: "시간당 최대 호출 횟수", fields: ["max_requests", "window_seconds"] },
  { value: "model_filter", label: "모델 필터", description: "허용/차단 모델 목록", fields: ["allowed_models", "blocked_models"] },
  { value: "time_restriction", label: "시간 제한", description: "허용 시간대 설정", fields: ["allowed_hours_start", "allowed_hours_end"] },
];

function generateRuleTemplate(ruleType: string): object[] {
  switch (ruleType) {
    case "content_filter":
      return [{ type: "content_filter", keywords: ["금지어1"], action: "DENY" }];
    case "cost_limit":
      return [{ type: "cost_limit", max_cost_usd: 10.0, period: "daily", action: "DENY" }];
    case "token_limit":
      return [{ type: "token_limit", max_tokens: 4096, action: "DENY" }];
    case "rate_limit":
      return [{ type: "rate_limit", max_requests: 100, window_seconds: 3600, action: "DENY" }];
    case "model_filter":
      return [{ type: "model_filter", allowed_models: ["claude-sonnet-4-20250514"], action: "DENY" }];
    case "time_restriction":
      return [{ type: "time_restriction", allowed_hours_start: 9, allowed_hours_end: 18, action: "DENY" }];
    default:
      return [{ type: ruleType }];
  }
}

const RULE_BORDER_CLASSES: Record<string, string> = {
  allow: "border-l-success",
  deny: "border-l-destructive",
  require_approval: "border-l-warning",
  transform: "border-l-info",
  rate_limit: "border-l-primary",
  log: "border-l-muted-foreground",
};

export default function Policies() {
  const { data: policies = [], isLoading } = usePolicies();
  const createPolicy = useCreatePolicy();
  const deletePolicy = useDeletePolicy();
  const simulatePolicy = useSimulatePolicy();

  const [expanded, setExpanded] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [simInput, setSimInput] = useState<Record<string, string>>({});
  const [simResults, setSimResults] = useState<Record<string, unknown>>({});
  const [form, setForm] = useState({ id: "", name: "", priority: "100", domain: "", matchPattern: "" });
  const [selectedRuleType, setSelectedRuleType] = useState("");
  const [rulesJson, setRulesJson] = useState("");

  const handleSimulate = async (id: string) => {
    const amount = simInput[id + "_amount"] ?? "";
    const result = await simulatePolicy.mutateAsync({
      id,
      data: { context: { intent: amount || "test_request", adapter: "anthropic", sessionId: "sim-" + id } },
    });
    setSimResults((p) => ({ ...p, [id]: result }));
  };

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={<ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>새 정책</ActionButton>}
    >

      {policies.length === 0 ? (
        <EmptyState
          icon={<Shield className="size-6" />}
          title="등록된 정책이 없습니다"
          description="allow / deny / require_approval / transform 규칙을 설정하세요"
          action={<ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>새 정책 추가</ActionButton>}
        />
      ) : (
        <div className="flex flex-col gap-3">
          {policies.map((policy: Policy) => {
            const isExpanded = expanded === policy.id;
            const rawSim = simResults[policy.id] as { action?: string; denyReason?: string; result?: string; reason?: string } | undefined;
            const simResult = rawSim ? { result: (rawSim.action ?? rawSim.result ?? "").toLowerCase(), reason: rawSim.denyReason ?? rawSim.reason ?? "" } : undefined;
            return (
              <div key={policy.id} className="bg-card border border-border rounded-xl overflow-hidden">
                {/* Header row */}
                <div
                  onClick={() => setExpanded(isExpanded ? null : policy.id)}
                  className="px-5 py-4 flex items-center gap-3 cursor-pointer justify-between"
                >
                  <div className="flex items-center gap-3">
                    <span className={cn("size-2 rounded-full shrink-0", policy.enabled !== false ? "bg-success" : "bg-muted-foreground/50")} />
                    <div>
                      <div className="text-sm font-semibold text-foreground">{policy.name}</div>
                      <div className="text-[11px] font-mono text-muted-foreground/60 mt-0.5">
                        {policy.matchPattern ?? policy.match ?? policy.id}
                      </div>
                    </div>
                  </div>
                  <div className="flex gap-2 items-center">
                    {policy.domain && <Badge color="muted">{policy.domain}</Badge>}
                    {policy.priority != null && <Badge color="accent">P{policy.priority}</Badge>}
                    <span className="text-xs font-mono text-muted-foreground/60">
                      {policy.ruleCount ?? policy.rules?.length ?? 0}개 규칙 · {policy.triggeredCount ?? 0}회 실행
                    </span>
                    <span className="text-muted-foreground/60 text-sm">{isExpanded ? "▲" : "▼"}</span>
                  </div>
                </div>

                {isExpanded && (
                  <div className="border-t border-border px-5 py-4">
                    {/* Rules */}
                    {(policy.rules ?? []).length > 0 && (
                      <div className="mb-4">
                        <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-2">규칙</div>
                        {(policy.rules ?? []).map((rule, i) => (
                          <div
                            key={i}
                            className={cn(
                              "flex gap-3 px-3.5 py-2.5 rounded-lg mb-1.5 bg-accent border-l-[3px]",
                              RULE_BORDER_CLASSES[rule.actionType ?? "allow"] ?? "border-l-muted-foreground"
                            )}
                          >
                            <div className="flex-1">
                              <div className="text-xs font-mono text-muted-foreground">IF {rule.condition}</div>
                              <div className="text-[13px] text-foreground mt-0.5">→ {rule.action}</div>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {/* Simulation */}
                    <div className="bg-accent rounded-lg px-4 py-3.5 mb-3">
                      <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-2">시뮬레이션</div>
                      <div className="flex gap-2 mb-2">
                        <input
                          style={{ ...inputStyle, flex: 1 }}
                          placeholder='{"amount": 150000}'
                          value={simInput[policy.id + "_amount"] ?? ""}
                          onChange={(e) => setSimInput((p) => ({ ...p, [policy.id + "_amount"]: e.target.value }))}
                        />
                        <ActionButton variant="default" small disabled={simulatePolicy.isPending} onClick={() => handleSimulate(policy.id)}>
                          테스트
                        </ActionButton>
                      </div>
                      {simResult && (
                        <div className={cn(
                          "text-xs font-mono px-3 py-2 rounded-md",
                          simResult.result === "allow" ? "bg-success/10 text-success"
                            : simResult.result === "deny" ? "bg-destructive/10 text-destructive"
                            : "bg-warning/10 text-warning"
                        )}>
                          결과: {simResult.result?.toUpperCase()} {simResult.reason ? `— ${simResult.reason}` : ""}
                        </div>
                      )}
                    </div>

                    <div className="flex gap-1.5">
                      <ActionButton variant="danger" small onClick={() => deletePolicy.mutate(policy.id)}>삭제</ActionButton>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      <Modal open={showModal} onClose={() => setShowModal(false)} title="새 정책 추가">
        <FormField label="정책 ID">
          <input style={inputStyle} value={form.id} onChange={(e) => setForm((p) => ({ ...p, id: e.target.value }))} />
        </FormField>
        <FormField label="정책 이름">
          <input style={inputStyle} value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="우선순위">
          <input type="number" style={inputStyle} value={form.priority} onChange={(e) => setForm((p) => ({ ...p, priority: e.target.value }))} />
        </FormField>
        <FormField label="도메인">
          <input style={inputStyle} placeholder="ecommerce" value={form.domain} onChange={(e) => setForm((p) => ({ ...p, domain: e.target.value }))} />
        </FormField>
        <FormField label="매칭 패턴">
          <input style={inputStyle} placeholder="process_refund → *" value={form.matchPattern} onChange={(e) => setForm((p) => ({ ...p, matchPattern: e.target.value }))} />
        </FormField>
        <FormField label="규칙 타입">
          <select
            style={inputStyle}
            value={selectedRuleType}
            onChange={(e) => {
              const ruleType = e.target.value;
              setSelectedRuleType(ruleType);
              if (ruleType) {
                setRulesJson(JSON.stringify(generateRuleTemplate(ruleType), null, 2));
              }
            }}
          >
            <option value="">직접 입력 (수동)</option>
            {RULE_TYPE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label} — {opt.description}</option>
            ))}
          </select>
        </FormField>

        {/* CR-015: 타입별 전용 입력 폼 */}
        {selectedRuleType && <RuleTypeForm ruleType={selectedRuleType} onUpdate={(updated) => setRulesJson(JSON.stringify(updated, null, 2))} />}

        <FormField label="규칙 (JSON)">
          <textarea
            style={{ ...inputStyle, minHeight: 120, resize: "vertical" }}
            className="font-mono text-xs"
            placeholder='[{ "type": "default", "action": "ALLOW", "params": {} }]'
            value={rulesJson}
            onChange={(e) => setRulesJson(e.target.value)}
          />
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="💾"
            disabled={createPolicy.isPending}
            onClick={() => {
              let rules: PolicyRule[];
              try {
                rules = rulesJson.trim() ? JSON.parse(rulesJson) : [{ condition: "*", action: "ALLOW", params: {} }];
              } catch {
                rules = [{ condition: "*", action: "ALLOW", params: {} }];
              }
              createPolicy.mutate(
                {
                  id: form.id || "policy-" + Date.now(),
                  name: form.name,
                  priority: Number(form.priority),
                  domain: form.domain,
                  matchRules: { pattern: form.matchPattern || "*" },
                  rules,
                },
                {
                  onSuccess: () => {
                    setShowModal(false);
                    setSelectedRuleType("");
                    setRulesJson("");
                  },
                }
              );
            }}
          >
            저장
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}

/* ─── CR-015: 타입별 전용 입력 폼 ─── */

const ACTION_OPTIONS = ["DENY", "REQUIRE_APPROVAL", "LOG", "ALLOW"];

function RuleTypeForm({ ruleType, onUpdate }: { ruleType: string; onUpdate: (rules: object[]) => void }) {
  const [params, setParams] = useState<Record<string, string>>({});

  const update = (key: string, value: string) => {
    const next = { ...params, [key]: value };
    setParams(next);
    onUpdate([buildRule(ruleType, next)]);
  };

  const action = params.action || "DENY";

  return (
    <div className="bg-accent rounded-lg px-3.5 py-3 mb-1">
      <div className="text-[11px] font-mono text-primary mb-2">타입별 설정</div>

      {/* 공통: 액션 선택 */}
      <div className="flex flex-col gap-1 mb-2">
        <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">위반 시 동작</div>
        <select style={inputStyle} value={action} onChange={(e) => update("action", e.target.value)}>
          {ACTION_OPTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
      </div>

      {ruleType === "content_filter" && (
        <div className="flex flex-col gap-1 mb-2">
          <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">금지 키워드 (쉼표 구분)</div>
          <input style={inputStyle} placeholder="비밀번호, 개인정보" value={params.keywords || ""} onChange={(e) => update("keywords", e.target.value)} />
        </div>
      )}
      {ruleType === "cost_limit" && (
        <>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">최대 비용 (USD)</div>
            <input type="number" style={inputStyle} placeholder="10.0" value={params.max_cost_usd || ""} onChange={(e) => update("max_cost_usd", e.target.value)} />
          </div>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">기간</div>
            <select style={inputStyle} value={params.period || "daily"} onChange={(e) => update("period", e.target.value)}>
              <option value="daily">일간</option>
              <option value="monthly">월간</option>
            </select>
          </div>
        </>
      )}
      {ruleType === "token_limit" && (
        <div className="flex flex-col gap-1 mb-2">
          <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">최대 토큰 수</div>
          <input type="number" style={inputStyle} placeholder="4096" value={params.max_tokens || ""} onChange={(e) => update("max_tokens", e.target.value)} />
        </div>
      )}
      {ruleType === "rate_limit" && (
        <>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">최대 요청 수</div>
            <input type="number" style={inputStyle} placeholder="100" value={params.max_requests || ""} onChange={(e) => update("max_requests", e.target.value)} />
          </div>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">윈도우 (초)</div>
            <input type="number" style={inputStyle} placeholder="3600" value={params.window_seconds || ""} onChange={(e) => update("window_seconds", e.target.value)} />
          </div>
        </>
      )}
      {ruleType === "model_filter" && (
        <>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">허용 모델 (쉼표 구분)</div>
            <input style={inputStyle} placeholder="claude-sonnet-4-20250514, gpt-4o" value={params.allowed_models || ""} onChange={(e) => update("allowed_models", e.target.value)} />
          </div>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">차단 모델 (쉼표 구분)</div>
            <input style={inputStyle} placeholder="gpt-3.5-turbo" value={params.blocked_models || ""} onChange={(e) => update("blocked_models", e.target.value)} />
          </div>
        </>
      )}
      {ruleType === "time_restriction" && (
        <>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">허용 시작 시간 (0-23)</div>
            <input type="number" style={inputStyle} placeholder="9" min="0" max="23" value={params.allowed_hours_start || ""} onChange={(e) => update("allowed_hours_start", e.target.value)} />
          </div>
          <div className="flex flex-col gap-1 mb-2">
            <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-tight">허용 종료 시간 (0-23)</div>
            <input type="number" style={inputStyle} placeholder="18" min="0" max="23" value={params.allowed_hours_end || ""} onChange={(e) => update("allowed_hours_end", e.target.value)} />
          </div>
        </>
      )}
    </div>
  );
}

function buildRule(ruleType: string, params: Record<string, string>): object {
  const action = params.action || "DENY";
  switch (ruleType) {
    case "content_filter":
      return { type: "content_filter", keywords: (params.keywords || "").split(",").map((s) => s.trim()).filter(Boolean), action };
    case "cost_limit":
      return { type: "cost_limit", max_cost_usd: parseFloat(params.max_cost_usd || "10"), period: params.period || "daily", action };
    case "token_limit":
      return { type: "token_limit", max_tokens: parseInt(params.max_tokens || "4096", 10), action };
    case "rate_limit":
      return { type: "rate_limit", max_requests: parseInt(params.max_requests || "100", 10), window_seconds: parseInt(params.window_seconds || "3600", 10), action };
    case "model_filter":
      return {
        type: "model_filter",
        allowed_models: (params.allowed_models || "").split(",").map((s) => s.trim()).filter(Boolean),
        blocked_models: (params.blocked_models || "").split(",").map((s) => s.trim()).filter(Boolean),
        action,
      };
    case "time_restriction":
      return { type: "time_restriction", allowed_hours_start: parseInt(params.allowed_hours_start || "9", 10), allowed_hours_end: parseInt(params.allowed_hours_end || "18", 10), action };
    default:
      return { type: ruleType, action };
  }
}
