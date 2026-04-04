import { useState } from "react";
import { cn } from "@/lib/utils";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, textareaStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { MessageSquare } from "lucide-react";
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
    <Page
      actions={<ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>새 프롬프트</ActionButton>}
    >

      {prompts.length === 0 ? (
        <EmptyState
          icon={<MessageSquare className="size-6" />}
          title="등록된 프롬프트가 없습니다"
          description="시스템 프롬프트와 사용자 프롬프트 템플릿을 관리하세요"
          action={<ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>새 프롬프트 추가</ActionButton>}
        />
      ) : (
        <div className="grid grid-cols-[280px_1fr] gap-5">
          {/* Prompt List */}
          <div className="bg-card border border-border rounded-xl overflow-hidden">
            <div className="px-4 py-3 border-b border-border text-xs font-mono text-muted-foreground uppercase tracking-wider">
              프롬프트 목록
            </div>
            <div className="overflow-y-auto max-h-[520px]">
              {prompts.map((p: Prompt) => (
                <div
                  key={p.id}
                  onClick={() => selectPrompt(p)}
                  className={cn(
                    "px-4 py-3 border-b border-border cursor-pointer transition-colors border-l-2",
                    selected?.id === p.id
                      ? "bg-accent border-l-primary"
                      : "bg-transparent border-l-transparent hover:bg-accent"
                  )}
                >
                  <div className="text-[13px] font-semibold text-foreground mb-1">{p.name ?? p.id}</div>
                  <div className="flex gap-1 flex-wrap">
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
            <div className="flex flex-col gap-4">
              <div className="bg-card border border-border rounded-xl overflow-hidden">
                <div className="px-5 py-3.5 border-b border-border flex justify-between items-center">
                  <div className="flex gap-2 items-center">
                    <span className="text-sm font-semibold text-foreground">{selected.name ?? selected.id}</span>
                    <Badge color="muted">v{selected.version ?? "1"}</Badge>
                  </div>
                  <div className="flex gap-1.5">
                    <ActionButton variant="ghost" small onClick={() => deletePrompt.mutate({ id: selected.id, version: selected.version })}>삭제</ActionButton>
                    <ActionButton variant="primary" small disabled={updatePrompt.isPending} onClick={handleSave}>저장</ActionButton>
                  </div>
                </div>

                <div className="p-5">
                  <textarea
                    style={{ ...textareaStyle, minHeight: 280, fontSize: 13, lineHeight: 1.6 }}
                    className="font-mono"
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                  />

                  {/* Variable chips */}
                  {variables.length > 0 && (
                    <div className="mt-3">
                      <div className="text-[11px] font-mono text-muted-foreground uppercase tracking-wider mb-1.5">변수</div>
                      <div className="flex gap-1.5 flex-wrap">
                        {variables.map((v) => (
                          <span
                            key={v}
                            onClick={() => insertVariable(v)}
                            className="inline-block px-2.5 py-0.5 rounded-md text-[11px] font-mono bg-primary/10 text-primary border border-primary/20 cursor-pointer hover:bg-primary/20 transition-colors"
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
                <div className="bg-card border border-warning/20 rounded-xl p-5">
                  <div className="text-sm font-semibold text-foreground mb-4">A/B 테스트 결과</div>
                  <div className="grid grid-cols-2 gap-3">
                    {(["variantA", "variantB"] as const).map((key) => {
                      const variant = selected.abTest?.[key];
                      const label = key === "variantA" ? "A" : "B";
                      return (
                        <div
                          key={key}
                          className={cn(
                            "px-3.5 py-3 rounded-lg bg-accent border",
                            selected.abTest?.winner === label ? "border-success" : "border-border"
                          )}
                        >
                          <div className="text-[13px] font-semibold text-foreground mb-2">
                            변형 {label} {selected.abTest?.winner === label && <Badge color="success">승자</Badge>}
                          </div>
                          <div className="text-xs font-mono text-muted-foreground space-y-0.5">
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
            <div className="bg-card border border-border rounded-xl flex items-center justify-center text-muted-foreground/60 font-mono text-[13px]">
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
            style={{ ...textareaStyle, minHeight: 140, fontSize: 12 }}
            className="font-mono"
            placeholder="당신은 {{shop_name}}의 고객 지원 AI입니다..."
            value={form.content}
            onChange={(e) => setForm((p) => ({ ...p, content: e.target.value }))}
          />
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
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
    </Page>
  );
}
