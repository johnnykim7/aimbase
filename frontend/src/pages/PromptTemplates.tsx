import { useState } from "react";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, textareaStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { LoadingSpinner } from "../components/common/LoadingSpinner";
import { Page } from "../components/layout/Page";
import { FileText, Trash2, Pencil, Play, RefreshCw } from "lucide-react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { promptTemplatesApi, type PromptTemplate } from "../api/promptTemplates";

const CATEGORIES = ["all", "core", "tool", "agent", "rag", "workflow", "evaluation", "service", "config"];

const emptyForm = {
  key: "",
  category: "tool",
  name: "",
  description: "",
  template: "",
  language: "en",
};

export default function PromptTemplates() {
  const queryClient = useQueryClient();
  const [category, setCategory] = useState("all");
  const [showModal, setShowModal] = useState(false);
  const [showTestModal, setShowTestModal] = useState(false);
  const [editItem, setEditItem] = useState<PromptTemplate | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [testKey, setTestKey] = useState("");
  const [testVersion, setTestVersion] = useState(1);
  const [testVars, setTestVars] = useState("{}");
  const [testResult, setTestResult] = useState("");

  const { data: templates = [], isLoading } = useQuery({
    queryKey: ["prompt-templates", category],
    queryFn: () =>
      promptTemplatesApi
        .list(category === "all" ? undefined : category)
        .then((r) => r.data.data ?? []),
  });

  const createTemplate = useMutation({
    mutationFn: (f: typeof emptyForm) =>
      promptTemplatesApi.create({
        key: f.key,
        category: f.category,
        name: f.name,
        description: f.description,
        template: f.template,
        language: f.language,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-templates"] });
      setShowModal(false);
    },
  });

  const updateTemplate = useMutation({
    mutationFn: ({ key, version, body }: { key: string; version: number; body: Partial<PromptTemplate> }) =>
      promptTemplatesApi.update(key, version, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-templates"] });
      setEditItem(null);
      setShowModal(false);
    },
  });

  const deleteTemplate = useMutation({
    mutationFn: ({ key, version }: { key: string; version: number }) =>
      promptTemplatesApi.delete(key, version),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["prompt-templates"] }),
  });

  const clearCache = useMutation({
    mutationFn: () => promptTemplatesApi.clearCache(),
  });

  const testRender = useMutation({
    mutationFn: () => {
      const vars = JSON.parse(testVars);
      return promptTemplatesApi.render(testKey, testVersion, vars);
    },
    onSuccess: (res) => {
      const data = res.data.data;
      setTestResult(`${data.rendered}\n\n--- Token estimate: ${data.token_estimate} ---`);
    },
    onError: (err: Error) => setTestResult(`Error: ${err.message}`),
  });

  const openEdit = (t: PromptTemplate) => {
    setEditItem(t);
    setForm({
      key: t.key,
      category: t.category,
      name: t.name,
      description: t.description || "",
      template: t.template,
      language: t.language,
    });
    setShowModal(true);
  };

  const openTest = (t: PromptTemplate) => {
    setTestKey(t.key);
    setTestVersion(t.version);
    setTestVars("{}");
    setTestResult("");
    setShowTestModal(true);
  };

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={
        <div className="flex gap-2">
          <ActionButton
            variant="outline"
            icon={<RefreshCw className="size-4" />}
            onClick={() => clearCache.mutate()}
            isLoading={clearCache.isPending}
          >
            캐시 초기화
          </ActionButton>
          <ActionButton
            variant="primary"
            icon="+"
            onClick={() => { setEditItem(null); setForm(emptyForm); setShowModal(true); }}
          >
            새 템플릿
          </ActionButton>
        </div>
      }
    >
      {/* 카테고리 필터 */}
      <div className="flex gap-2 mb-4 flex-wrap">
        {CATEGORIES.map((c) => (
          <button
            key={c}
            onClick={() => setCategory(c)}
            className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
              category === c
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground hover:bg-muted/80"
            }`}
          >
            {c}
          </button>
        ))}
      </div>

      {templates.length === 0 ? (
        <EmptyState
          icon={<FileText className="size-6" />}
          title="프롬프트 템플릿이 없습니다"
          description="시스템 프롬프트를 외부화하여 런타임에 관리하세요"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              템플릿 추가
            </ActionButton>
          }
        />
      ) : (
        <div className="space-y-2">
          {templates.map((t: PromptTemplate) => (
            <div
              key={`${t.key}-${t.version}`}
              className="bg-card border border-border rounded-xl p-4 flex items-start gap-4"
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <h3 className="font-semibold text-sm truncate">{t.name}</h3>
                  <Badge variant="outline">{t.category}</Badge>
                  <Badge variant={t.is_system ? "default" : "outline"}>
                    {t.is_system ? "system" : "custom"}
                  </Badge>
                  <span className="text-xs text-muted-foreground">v{t.version}</span>
                </div>
                <p className="text-xs text-muted-foreground font-mono truncate">{t.key}</p>
                <p className="text-sm text-muted-foreground line-clamp-2 mt-1">
                  {t.template.substring(0, 150)}...
                </p>
              </div>
              <div className="flex gap-1 shrink-0">
                <button
                  className="p-1.5 rounded hover:bg-accent"
                  onClick={() => openTest(t)}
                  title="테스트"
                >
                  <Play className="size-4 text-primary" />
                </button>
                <button
                  className="p-1.5 rounded hover:bg-accent"
                  onClick={() => openEdit(t)}
                  title="편집"
                >
                  <Pencil className="size-4 text-muted-foreground" />
                </button>
                {!t.is_system && (
                  <button
                    className="p-1.5 rounded hover:bg-destructive/10"
                    onClick={() => {
                      if (confirm(`'${t.key}' 템플릿을 삭제하시겠습니까?`))
                        deleteTemplate.mutate({ key: t.key, version: t.version });
                    }}
                    title="삭제"
                  >
                    <Trash2 className="size-4 text-destructive" />
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 생성/편집 모달 */}
      <Modal
        open={showModal}
        onClose={() => { setShowModal(false); setEditItem(null); }}
        title={editItem ? "프롬프트 템플릿 편집" : "새 프롬프트 템플릿"}
        footer={
          <ActionButton
            variant="primary"
            onClick={() => {
              if (editItem) {
                updateTemplate.mutate({
                  key: editItem.key,
                  version: editItem.version,
                  body: { name: form.name, description: form.description, template: form.template, language: form.language },
                });
              } else {
                createTemplate.mutate(form);
              }
            }}
            isLoading={createTemplate.isPending || updateTemplate.isPending}
          >
            {editItem ? "저장" : "생성"}
          </ActionButton>
        }
      >
        <div className="space-y-4">
          {!editItem && (
            <>
              <FormField label="Key" hint="예: tool.bash.prompt">
                <input
                  className={inputStyle}
                  value={form.key}
                  onChange={(e) => setForm({ ...form, key: e.target.value })}
                  placeholder="category.subcategory.identifier"
                />
              </FormField>
              <FormField label="카테고리">
                <select
                  className={inputStyle}
                  value={form.category}
                  onChange={(e) => setForm({ ...form, category: e.target.value })}
                >
                  {CATEGORIES.filter((c) => c !== "all").map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </FormField>
            </>
          )}
          <FormField label="이름">
            <input
              className={inputStyle}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="Bash Tool Prompt"
            />
          </FormField>
          <FormField label="설명">
            <input
              className={inputStyle}
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
            />
          </FormField>
          <FormField label="템플릿" hint="{{variable}} 형식으로 변수 사용">
            <textarea
              className={textareaStyle}
              rows={12}
              value={form.template}
              onChange={(e) => setForm({ ...form, template: e.target.value })}
              style={{ fontFamily: "monospace", fontSize: "13px" }}
            />
          </FormField>
          <FormField label="언어">
            <select
              className={inputStyle}
              value={form.language}
              onChange={(e) => setForm({ ...form, language: e.target.value })}
            >
              <option value="en">English</option>
              <option value="ko">Korean</option>
            </select>
          </FormField>
        </div>
      </Modal>

      {/* 테스트 모달 */}
      <Modal
        open={showTestModal}
        onClose={() => setShowTestModal(false)}
        title="프롬프트 렌더링 테스트"
        footer={
          <ActionButton
            variant="primary"
            onClick={() => testRender.mutate()}
            isLoading={testRender.isPending}
          >
            렌더링
          </ActionButton>
        }
      >
        <div className="space-y-4">
          <FormField label="Key">
            <input className={inputStyle} value={testKey} disabled />
          </FormField>
          <FormField label="변수 (JSON)" hint='예: {"query": "test question"}'>
            <textarea
              className={textareaStyle}
              rows={3}
              value={testVars}
              onChange={(e) => setTestVars(e.target.value)}
              style={{ fontFamily: "monospace" }}
            />
          </FormField>
          {testResult && (
            <FormField label="렌더링 결과">
              <pre className="bg-muted rounded-lg p-3 text-sm whitespace-pre-wrap max-h-64 overflow-auto">
                {testResult}
              </pre>
            </FormField>
          )}
        </div>
      </Modal>
    </Page>
  );
}
