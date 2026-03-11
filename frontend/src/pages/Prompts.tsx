import { useState } from "react";
import { COLORS, FONTS } from "../theme";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, textareaStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { PageHeader } from "../components/layout/PageHeader";
import { usePrompts, useCreatePrompt, useDeletePrompt, useUpdatePrompt } from "../hooks/usePrompts";
import type { Prompt } from "../types/prompt";

function extractVariables(content: string): string[] {
  const matches = content.match(/\{\{(\w+)\}\}/g) ?? [];
  return [...new Set(matches.map((m) => m.slice(2, -2)))];
}

export default function Prompts() {
  const { data: prompts = [], isLoading } = usePrompts();
  const createPrompt = useCreatePrompt();
  const updatePrompt = useUpdatePrompt();
  const deletePrompt = useDeletePrompt();

  const [selected, setSelected] = useState<Prompt | null>(null);
  const [editContent, setEditContent] = useState("");
  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState({ id: "", name: "", description: "", domain: "", content: "" });

  const selectPrompt = (p: Prompt) => {
    setSelected(p);
    setEditContent(p.content ?? "");
  };

  const handleSave = () => {
    if (!selected) return;
    updatePrompt.mutate(
      { id: selected.id, version: selected.version ?? "1", data: { content: editContent } },
      { onSuccess: () => setSelected((p) => (p ? { ...p, content: editContent } : null)) }
    );
  };

  const insertVariable = (v: string) => {
    setEditContent((prev) => prev + `{{${v}}}`);
  };

  const variables = selected ? extractVariables(editContent) : [];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title="프롬프트 관리"
        subtitle="LLM 프롬프트 템플릿 편집 및 버전 관리"
        actions={
          <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
            새 프롬프트
          </ActionButton>
        }
      />

      {prompts.length === 0 ? (
        <EmptyState
          icon="💬"
          title="등록된 프롬프트가 없습니다"
          description="시스템 프롬프트와 사용자 프롬프트 템플릿을 관리하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              새 프롬프트 추가
            </ActionButton>
          }
        />
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "280px 1fr", gap: 20 }}>
          {/* Prompt List */}
          <div
            style={{
              background: COLORS.surface,
              border: `1px solid ${COLORS.border}`,
              borderRadius: 12,
              overflow: "hidden",
            }}
          >
            <div
              style={{
                padding: "12px 16px",
                borderBottom: `1px solid ${COLORS.border}`,
                fontSize: 12,
                fontFamily: FONTS.mono,
                color: COLORS.textMuted,
                textTransform: "uppercase",
                letterSpacing: 1,
              }}
            >
              프롬프트 목록
            </div>
            <div style={{ overflowY: "auto", maxHeight: 520 }}>
              {prompts.map((p: Prompt) => (
                <div
                  key={p.id}
                  onClick={() => selectPrompt(p)}
                  style={{
                    padding: "12px 16px",
                    borderBottom: `1px solid ${COLORS.border}`,
                    cursor: "pointer",
                    background: selected?.id === p.id ? COLORS.surfaceActive : "transparent",
                    borderLeft: selected?.id === p.id ? `2px solid ${COLORS.accent}` : "2px solid transparent",
                    transition: "background 0.15s",
                  }}
                >
                  <div style={{ fontSize: 13, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 4 }}>
                    {p.name ?? p.id}
                  </div>
                  <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
                    <Badge color="muted">v{p.version ?? "1"}</Badge>
                    {p.domain && <Badge color="purple">{p.domain}</Badge>}
                    {p.abTestActive && <Badge color="warning">A/B</Badge>}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Editor Panel */}
          {selected ? (
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
              <div
                style={{
                  background: COLORS.surface,
                  border: `1px solid ${COLORS.border}`,
                  borderRadius: 12,
                  overflow: "hidden",
                }}
              >
                <div
                  style={{
                    padding: "14px 20px",
                    borderBottom: `1px solid ${COLORS.border}`,
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                  }}
                >
                  <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                    <span style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>
                      {selected.name ?? selected.id}
                    </span>
                    <Badge color="muted">v{selected.version ?? "1"}</Badge>
                  </div>
                  <div style={{ display: "flex", gap: 6 }}>
                    <ActionButton variant="ghost" small onClick={() => deletePrompt.mutate({ id: selected.id, version: selected.version })}>
                      삭제
                    </ActionButton>
                    <ActionButton variant="primary" small disabled={updatePrompt.isPending} onClick={handleSave}>
                      저장
                    </ActionButton>
                  </div>
                </div>

                <div style={{ padding: 20 }}>
                  <textarea
                    style={{
                      ...textareaStyle,
                      minHeight: 280,
                      fontFamily: FONTS.mono,
                      fontSize: 13,
                      lineHeight: 1.6,
                    }}
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                  />

                  {/* Variable chips */}
                  {variables.length > 0 && (
                    <div style={{ marginTop: 12 }}>
                      <div style={{ fontSize: 11, fontFamily: FONTS.mono, color: COLORS.textMuted, textTransform: "uppercase", letterSpacing: 1, marginBottom: 6 }}>
                        변수
                      </div>
                      <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                        {variables.map((v) => (
                          <span
                            key={v}
                            onClick={() => insertVariable(v)}
                            style={{
                              display: "inline-block",
                              padding: "3px 10px",
                              borderRadius: 6,
                              fontSize: 11,
                              fontFamily: FONTS.mono,
                              background: COLORS.accentDim + "30",
                              color: COLORS.accent,
                              border: `1px solid ${COLORS.accentDim}`,
                              cursor: "pointer",
                            }}
                          >
                            {`{{${v}}}`}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {/* A/B Test Panel */}
              {selected.abTestActive && selected.abTest && (
                <div
                  style={{
                    background: COLORS.surface,
                    border: `1px solid ${COLORS.warning}30`,
                    borderRadius: 12,
                    padding: 20,
                  }}
                >
                  <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 16 }}>
                    A/B 테스트 결과
                  </div>
                  <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
                    {(["variantA", "variantB"] as const).map((key) => {
                      const variant = selected.abTest?.[key];
                      const label = key === "variantA" ? "A" : "B";
                      return (
                        <div
                          key={key}
                          style={{
                            padding: "12px 14px",
                            borderRadius: 8,
                            background: COLORS.surfaceHover,
                            border: `1px solid ${selected.abTest?.winner === label ? COLORS.success : COLORS.border}`,
                          }}
                        >
                          <div style={{ fontSize: 13, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 8 }}>
                            변형 {label} {selected.abTest?.winner === label && <Badge color="success">승자</Badge>}
                          </div>
                          <div style={{ fontSize: 12, fontFamily: FONTS.mono, color: COLORS.textMuted }}>
                            <div>만족도: {variant?.satisfaction != null ? `${(variant.satisfaction * 100).toFixed(0)}%` : "—"}</div>
                            <div>평균 응답시간: {variant?.avgMs != null ? `${variant.avgMs}ms` : "—"}</div>
                            <div>샘플 수: {variant?.count ?? "—"}</div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div
              style={{
                background: COLORS.surface,
                border: `1px solid ${COLORS.border}`,
                borderRadius: 12,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                color: COLORS.textDim,
                fontFamily: FONTS.mono,
                fontSize: 13,
              }}
            >
              프롬프트를 선택하세요
            </div>
          )}
        </div>
      )}

      {/* Create Modal */}
      <Modal open={showModal} onClose={() => setShowModal(false)} title="새 프롬프트 생성">
        <FormField label="프롬프트 ID">
          <input style={inputStyle} placeholder="my_prompt" value={form.id} onChange={(e) => setForm((p) => ({ ...p, id: e.target.value }))} />
        </FormField>
        <FormField label="이름">
          <input style={inputStyle} placeholder="고객 지원 시스템 프롬프트" value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="도메인">
          <input style={inputStyle} placeholder="ecommerce" value={form.domain} onChange={(e) => setForm((p) => ({ ...p, domain: e.target.value }))} />
        </FormField>
        <FormField label="프롬프트 내용">
          <textarea
            style={{ ...textareaStyle, minHeight: 140, fontFamily: FONTS.mono, fontSize: 12 }}
            placeholder="당신은 {{shop_name}}의 고객 지원 AI입니다..."
            value={form.content}
            onChange={(e) => setForm((p) => ({ ...p, content: e.target.value }))}
          />
        </FormField>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="💾"
            disabled={createPrompt.isPending}
            onClick={() => {
              createPrompt.mutate(
                { id: form.id, name: form.name, domain: form.domain, content: form.content },
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
