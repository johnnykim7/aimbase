import { useState } from "react";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { DataTable, type Column } from "../components/common/DataTable";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { Layers, Plus, Trash2, ArrowUp, ArrowDown } from "lucide-react";
import {
  useContextRecipes,
  useCreateContextRecipe,
  useUpdateContextRecipe,
  useDeleteContextRecipe,
} from "../hooks/useContextRecipes";
import type { ContextRecipe, ContextRecipeRequest, ContextSourceConfig, ContextSourceType } from "../types/contextRecipe";

const SOURCE_TYPES: ContextSourceType[] = [
  "system_policy",
  "session_summary",
  "recent_conversation",
  "tool_contract",
  "rag_knowledge",
  "project_context",
  "domain_prompt",
  "user_preference",
];

const emptySource = (): ContextSourceConfig => ({
  source: "system_policy",
  enabled: true,
  maxTokens: 2048,
  priority: 0,
});

export default function ContextRecipes() {
  const { data: recipes = [], isLoading } = useContextRecipes();
  const createRecipe = useCreateContextRecipe();
  const updateRecipe = useUpdateContextRecipe();
  const deleteRecipe = useDeleteContextRecipe();

  const [showModal, setShowModal] = useState(false);
  const [editTarget, setEditTarget] = useState<ContextRecipe | null>(null);
  const [form, setForm] = useState<ContextRecipeRequest>({ name: "", description: "", domainApp: "", sources: [] });

  const openCreate = () => {
    setEditTarget(null);
    setForm({ name: "", description: "", domainApp: "", sources: [emptySource()] });
    setShowModal(true);
  };

  const openEdit = (r: ContextRecipe) => {
    setEditTarget(r);
    setForm({
      name: r.name,
      description: r.description ?? "",
      domainApp: r.domainApp ?? "",
      sources: r.sources && r.sources.length > 0 ? [...r.sources] : [emptySource()],
      active: r.active,
    });
    setShowModal(true);
  };

  const handleSave = () => {
    if (editTarget) {
      updateRecipe.mutate(
        { id: editTarget.id, data: form },
        { onSuccess: () => setShowModal(false) }
      );
    } else {
      createRecipe.mutate(form, { onSuccess: () => setShowModal(false) });
    }
  };

  const moveSource = (idx: number, dir: -1 | 1) => {
    const sources = [...(form.sources ?? [])];
    const target = idx + dir;
    if (target < 0 || target >= sources.length) return;
    [sources[idx], sources[target]] = [sources[target], sources[idx]];
    setForm((p) => ({ ...p, sources }));
  };

  const removeSource = (idx: number) => {
    setForm((p) => ({ ...p, sources: (p.sources ?? []).filter((_, i) => i !== idx) }));
  };

  const updateSource = (idx: number, patch: Partial<ContextSourceConfig>) => {
    const sources = [...(form.sources ?? [])];
    sources[idx] = { ...sources[idx], ...patch };
    setForm((p) => ({ ...p, sources }));
  };

  const columns: Column<ContextRecipe>[] = [
    {
      header: "Name",
      render: (r) => <span className="font-mono text-primary text-[13px]">{r.name}</span>,
    },
    {
      header: "Domain",
      render: (r) =>
        r.domainApp ? <Badge color="purple">{r.domainApp}</Badge> : <span className="text-muted-foreground/60">--</span>,
      width: "130px",
    },
    {
      header: "Source Count",
      render: (r) => (
        <span className="text-xs font-mono text-muted-foreground">{r.sources?.length ?? 0}</span>
      ),
      width: "110px",
    },
    {
      header: "Active",
      render: (r) => (
        <Badge color={r.active !== false ? "success" : "muted"}>
          {r.active !== false ? "ON" : "OFF"}
        </Badge>
      ),
      width: "80px",
    },
    {
      header: "Created",
      render: (r) => (
        <span className="text-xs text-muted-foreground">
          {r.createdAt ? new Date(r.createdAt).toLocaleDateString("ko-KR") : "--"}
        </span>
      ),
      width: "110px",
    },
    {
      header: "액션",
      render: (r) => (
        <div className="flex gap-1.5">
          <ActionButton small variant="ghost" onClick={() => openEdit(r)}>편집</ActionButton>
          <ActionButton small variant="danger" onClick={() => deleteRecipe.mutate(r.id)}>삭제</ActionButton>
        </div>
      ),
      width: "140px",
    },
  ];

  return (
    <Page
      actions={<ActionButton variant="primary" icon="+" onClick={openCreate}>새 Recipe</ActionButton>}
    >
      {recipes.length === 0 && !isLoading ? (
        <EmptyState
          icon={<Layers className="size-6" />}
          title="Context Recipe가 없습니다"
          description="컨텍스트 조립 전략을 정의하는 Recipe를 생성하세요"
          action={<ActionButton variant="primary" icon="+" onClick={openCreate}>새 Recipe 추가</ActionButton>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={recipes}
          keyExtractor={(r) => r.id}
          loading={isLoading}
          emptyMessage="Recipe가 없습니다"
        />
      )}

      {/* Create/Edit Modal */}
      <Modal open={showModal} onClose={() => setShowModal(false)} title={editTarget ? "Recipe 편집" : "새 Recipe 생성"} width={620}>
        <FormField label="이름">
          <input style={inputStyle} value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="설명">
          <input style={inputStyle} value={form.description ?? ""} onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))} />
        </FormField>
        <FormField label="도메인 앱">
          <input style={inputStyle} placeholder="chatpilot" value={form.domainApp ?? ""} onChange={(e) => setForm((p) => ({ ...p, domainApp: e.target.value }))} />
        </FormField>

        {/* Sources */}
        <div className="mb-4">
          <div className="flex items-center justify-between mb-2">
            <label className="block text-xs font-medium text-foreground">Sources (우선순위 순서)</label>
            <button
              onClick={() => setForm((p) => ({ ...p, sources: [...(p.sources ?? []), emptySource()] }))}
              className="inline-flex items-center gap-1 text-xs text-primary hover:underline cursor-pointer bg-transparent border-none"
            >
              <Plus className="size-3" /> 추가
            </button>
          </div>
          <div className="flex flex-col gap-2">
            {(form.sources ?? []).map((src, idx) => (
              <div key={idx} className="flex items-center gap-2 bg-accent rounded-lg px-3 py-2">
                <span className="text-[11px] font-mono text-muted-foreground/60 w-5 shrink-0">{idx + 1}</span>
                <select
                  style={{ ...inputStyle, flex: 1 }}
                  value={src.source}
                  onChange={(e) => updateSource(idx, { source: e.target.value as ContextSourceType })}
                >
                  {SOURCE_TYPES.map((t) => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </select>
                <input
                  type="number"
                  style={{ ...inputStyle, width: 80 }}
                  placeholder="tokens"
                  value={src.maxTokens ?? ""}
                  onChange={(e) => updateSource(idx, { maxTokens: Number(e.target.value) || undefined })}
                />
                <label className="flex items-center gap-1 text-[11px] text-muted-foreground cursor-pointer">
                  <input
                    type="checkbox"
                    checked={src.enabled}
                    onChange={(e) => updateSource(idx, { enabled: e.target.checked })}
                  />
                  ON
                </label>
                <button onClick={() => moveSource(idx, -1)} disabled={idx === 0} className="p-0.5 text-muted-foreground hover:text-foreground disabled:opacity-30 cursor-pointer bg-transparent border-none">
                  <ArrowUp className="size-3.5" />
                </button>
                <button onClick={() => moveSource(idx, 1)} disabled={idx === (form.sources?.length ?? 0) - 1} className="p-0.5 text-muted-foreground hover:text-foreground disabled:opacity-30 cursor-pointer bg-transparent border-none">
                  <ArrowDown className="size-3.5" />
                </button>
                <button onClick={() => removeSource(idx)} className="p-0.5 text-muted-foreground hover:text-destructive cursor-pointer bg-transparent border-none">
                  <Trash2 className="size-3.5" />
                </button>
              </div>
            ))}
          </div>
        </div>

        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            disabled={createRecipe.isPending || updateRecipe.isPending || !form.name}
            onClick={handleSave}
          >
            저장
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
