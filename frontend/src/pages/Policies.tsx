import { useState } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
import {
  usePolicies,
  useCreatePolicy,
  useDeletePolicy,
  useSimulatePolicy,
} from "../hooks/usePolicies";
import type { Policy } from "../types/policy";

const RULE_COLORS: Record<string, string> = {
  allow: COLORS.success,
  deny: COLORS.danger,
  require_approval: COLORS.warning,
  transform: COLORS.purple,
  rate_limit: COLORS.accent,
  log: COLORS.textMuted,
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

  const handleSimulate = async (id: string) => {
    const context = Object.fromEntries(
      Object.entries(simInput)
        .filter(([k]) => k.startsWith(id + "_"))
        .map(([k, v]) => [k.replace(id + "_", ""), v])
    );
    const result = await simulatePolicy.mutateAsync({ id, data: { context } });
    setSimResults((p) => ({ ...p, [id]: result }));
  };

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title="정책 관리"
        subtitle="Policy Engine — 액션 실행 전 규칙 평가"
        actions={
          <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
            새 정책
          </ActionButton>
        }
      />

      {policies.length === 0 ? (
        <EmptyState
          icon="🛡️"
          title="등록된 정책이 없습니다"
          description="allow / deny / require_approval / transform 규칙을 설정하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              새 정책 추가
            </ActionButton>
          }
        />
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {policies.map((policy: Policy) => {
            const isExpanded = expanded === policy.id;
            const simResult = simResults[policy.id] as { result?: string; reason?: string } | undefined;
            return (
              <div
                key={policy.id}
                style={{
                  background: COLORS.surface,
                  border: `1px solid ${COLORS.border}`,
                  borderRadius: 12,
                  overflow: "hidden",
                }}
              >
                {/* Header row */}
                <div
                  onClick={() => setExpanded(isExpanded ? null : policy.id)}
                  style={{
                    padding: "16px 20px",
                    display: "flex",
                    alignItems: "center",
                    gap: 12,
                    cursor: "pointer",
                    justifyContent: "space-between",
                  }}
                >
                  <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                    <span
                      style={{
                        width: 8,
                        height: 8,
                        borderRadius: "50%",
                        background: policy.enabled !== false ? COLORS.success : COLORS.textDim,
                        flexShrink: 0,
                      }}
                    />
                    <div>
                      <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
                        {policy.name}
                      </div>
                      <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textDim, marginTop: 2 }}>
                        {policy.matchPattern ?? policy.match ?? policy.id}
                      </div>
                    </div>
                  </div>
                  <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                    {policy.domain && <Badge color="muted">{policy.domain}</Badge>}
                    {policy.priority != null && (
                      <Badge color="accent">P{policy.priority}</Badge>
                    )}
                    <span style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textDim }}>
                      {policy.ruleCount ?? policy.rules?.length ?? 0}개 규칙 · {policy.triggeredCount ?? 0}회 실행
                    </span>
                    <span style={{ color: COLORS.textDim, fontSize: 14 }}>{isExpanded ? "▲" : "▼"}</span>
                  </div>
                </div>

                {isExpanded && (
                  <div style={{ borderTop: `1px solid ${COLORS.border}`, padding: "16px 20px" }}>
                    {/* Rules */}
                    {(policy.rules ?? []).length > 0 && (
                      <div style={{ marginBottom: 16 }}>
                        <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 8 }}>
                          규칙
                        </div>
                        {(policy.rules ?? []).map((rule, i) => (
                          <div
                            key={i}
                            style={{
                              display: "flex",
                              gap: 12,
                              padding: "10px 14px",
                              borderRadius: 8,
                              marginBottom: 6,
                              background: COLORS.surfaceHover,
                              borderLeft: `3px solid ${RULE_COLORS[rule.actionType ?? "allow"] ?? COLORS.textDim}`,
                            }}
                          >
                            <div style={{ flex: 1 }}>
                              <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textMuted }}>
                                IF {rule.condition}
                              </div>
                              <div style={{ fontSize: 13, fontFamily: FONTS.sans, color: COLORS.text, marginTop: 3 }}>
                                → {rule.action}
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {/* Simulation */}
                    <div
                      style={{
                        background: COLORS.surfaceHover,
                        borderRadius: 8,
                        padding: "14px 16px",
                        marginBottom: 12,
                      }}
                    >
                      <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 8 }}>
                        시뮬레이션
                      </div>
                      <div style={{ display: "flex", gap: 8, marginBottom: 8 }}>
                        <input
                          style={{ ...inputStyle, flex: 1 }}
                          placeholder='{"amount": 150000}'
                          value={simInput[policy.id + "_amount"] ?? ""}
                          onChange={(e) =>
                            setSimInput((p) => ({ ...p, [policy.id + "_amount"]: e.target.value }))
                          }
                        />
                        <ActionButton
                          variant="default"
                          small
                          disabled={simulatePolicy.isPending}
                          onClick={() => handleSimulate(policy.id)}
                        >
                          테스트
                        </ActionButton>
                      </div>
                      {simResult && (
                        <div
                          style={{
                            fontSize: 12,
                            fontFamily: FONTS.mono,
                            padding: "8px 12px",
                            borderRadius: 6,
                            background:
                              simResult.result === "allow"
                                ? COLORS.successDim + "40"
                                : simResult.result === "deny"
                                ? COLORS.dangerDim + "40"
                                : COLORS.warningDim + "40",
                            color:
                              simResult.result === "allow"
                                ? COLORS.success
                                : simResult.result === "deny"
                                ? COLORS.danger
                                : COLORS.warning,
                          }}
                        >
                          결과: {simResult.result?.toUpperCase()} {simResult.reason ? `— ${simResult.reason}` : ""}
                        </div>
                      )}
                    </div>

                    <div style={{ display: "flex", gap: 6 }}>
                      <ActionButton variant="danger" small onClick={() => deletePolicy.mutate(policy.id)}>
                        삭제
                      </ActionButton>
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
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="💾"
            disabled={createPolicy.isPending}
            onClick={() => {
              createPolicy.mutate(
                { id: form.id, name: form.name, priority: Number(form.priority), domain: form.domain, matchPattern: form.matchPattern, enabled: true },
                { onSuccess: () => setShowModal(false) }
              );
            }}
          >
            저장
          </ActionButton>
        </div>
      </Modal>
    </div>
  );
}
