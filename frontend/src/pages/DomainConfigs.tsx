import { useState } from "react";
import { Badge } from "../components/common/Badge";
import { ActionButton } from "../components/common/ActionButton";
import { DataTable, type Column } from "../components/common/DataTable";
import { Modal } from "../components/common/Modal";
import { FormField, inputStyle, textareaStyle } from "../components/common/FormField";
import { EmptyState } from "../components/common/EmptyState";
import { Page } from "../components/layout/Page";
import { Globe } from "lucide-react";
import {
  useDomainConfigs,
  useCreateDomainConfig,
  useUpdateDomainConfig,
  useDeleteDomainConfig,
} from "../hooks/useDomainConfigs";
import type { DomainAppConfig } from "../types/domain";

type FormState = {
  domainApp: string;
  defaultContextRecipeId: string;
  defaultRuntime: string;
  defaultSessionScope: string;
  defaultToolAllowlist: string;
};

const emptyForm = (): FormState => ({
  domainApp: "",
  defaultContextRecipeId: "",
  defaultRuntime: "",
  defaultSessionScope: "",
  defaultToolAllowlist: "[]",
});

export default function DomainConfigs() {
  const { data: configs = [], isLoading } = useDomainConfigs();
  const createConfig = useCreateDomainConfig();
  const updateConfig = useUpdateDomainConfig();
  const deleteConfig = useDeleteDomainConfig();

  const [showModal, setShowModal] = useState(false);
  const [editTarget, setEditTarget] = useState<DomainAppConfig | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm());

  const openCreate = () => {
    setEditTarget(null);
    setForm(emptyForm());
    setShowModal(true);
  };

  const openEdit = (c: DomainAppConfig) => {
    setEditTarget(c);
    setForm({
      domainApp: c.domainApp,
      defaultContextRecipeId: c.defaultContextRecipeId ?? "",
      defaultRuntime: c.defaultRuntime ?? "",
      defaultSessionScope: c.defaultSessionScope ?? "",
      defaultToolAllowlist: c.defaultToolAllowlist ? JSON.stringify(c.defaultToolAllowlist, null, 2) : "[]",
    });
    setShowModal(true);
  };

  const handleSave = () => {
    let allowlist: string[] = [];
    try {
      allowlist = JSON.parse(form.defaultToolAllowlist);
    } catch {
      // keep empty
    }

    const body = {
      domainApp: form.domainApp,
      defaultContextRecipeId: form.defaultContextRecipeId || undefined,
      defaultRuntime: form.defaultRuntime || undefined,
      defaultSessionScope: form.defaultSessionScope || undefined,
      defaultToolAllowlist: allowlist.length > 0 ? allowlist : undefined,
    };

    if (editTarget) {
      updateConfig.mutate(
        { domainApp: editTarget.domainApp, data: body },
        { onSuccess: () => setShowModal(false) }
      );
    } else {
      createConfig.mutate(body, { onSuccess: () => setShowModal(false) });
    }
  };

  const columns: Column<DomainAppConfig>[] = [
    {
      header: "Domain App",
      render: (c) => <span className="font-mono text-primary text-[13px]">{c.domainApp}</span>,
    },
    {
      header: "Default Recipe",
      render: (c) =>
        c.defaultContextRecipeId ? (
          <span className="text-xs font-mono text-muted-foreground">{c.defaultContextRecipeId}</span>
        ) : (
          <span className="text-muted-foreground/60">--</span>
        ),
      width: "160px",
    },
    {
      header: "Default Runtime",
      render: (c) =>
        c.defaultRuntime ? (
          <Badge color="accent">{c.defaultRuntime}</Badge>
        ) : (
          <span className="text-muted-foreground/60">--</span>
        ),
      width: "140px",
    },
    {
      header: "Session Scope",
      render: (c) =>
        c.defaultSessionScope ? (
          <Badge color="muted">{c.defaultSessionScope}</Badge>
        ) : (
          <span className="text-muted-foreground/60">--</span>
        ),
      width: "130px",
    },
    {
      header: "Created",
      render: (c) => (
        <span className="text-xs text-muted-foreground">
          {c.createdAt ? new Date(c.createdAt).toLocaleDateString("ko-KR") : "--"}
        </span>
      ),
      width: "110px",
    },
    {
      header: "액션",
      render: (c) => (
        <div className="flex gap-1.5">
          <ActionButton small variant="ghost" onClick={() => openEdit(c)}>편집</ActionButton>
          <ActionButton small variant="danger" onClick={() => deleteConfig.mutate(c.domainApp)}>삭제</ActionButton>
        </div>
      ),
      width: "140px",
    },
  ];

  return (
    <Page
      actions={<ActionButton variant="primary" icon="+" onClick={openCreate}>새 설정</ActionButton>}
    >
      {configs.length === 0 && !isLoading ? (
        <EmptyState
          icon={<Globe className="size-6" />}
          title="도메인 설정이 없습니다"
          description="도메인 앱별 기본 런타임, Recipe, 세션 스코프를 설정하세요"
          action={<ActionButton variant="primary" icon="+" onClick={openCreate}>새 설정 추가</ActionButton>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={configs}
          keyExtractor={(c) => c.id ?? c.domainApp}
          loading={isLoading}
          emptyMessage="도메인 설정이 없습니다"
        />
      )}

      {/* Create/Edit Modal */}
      <Modal open={showModal} onClose={() => setShowModal(false)} title={editTarget ? "도메인 설정 편집" : "새 도메인 설정"}>
        <FormField label="도메인 앱">
          <input
            style={inputStyle}
            placeholder="chatpilot"
            value={form.domainApp}
            disabled={!!editTarget}
            onChange={(e) => setForm((p) => ({ ...p, domainApp: e.target.value }))}
          />
        </FormField>
        <FormField label="기본 Context Recipe ID">
          <input
            style={inputStyle}
            placeholder="recipe-id"
            value={form.defaultContextRecipeId}
            onChange={(e) => setForm((p) => ({ ...p, defaultContextRecipeId: e.target.value }))}
          />
        </FormField>
        <FormField label="기본 Runtime">
          <select
            style={inputStyle}
            value={form.defaultRuntime}
            onChange={(e) => setForm((p) => ({ ...p, defaultRuntime: e.target.value }))}
          >
            <option value="">선택 안함</option>
            <option value="claude_tool">claude_tool</option>
            <option value="llm_api">llm_api</option>
            <option value="mcp_only">mcp_only</option>
          </select>
        </FormField>
        <FormField label="기본 Session Scope">
          <select
            style={inputStyle}
            value={form.defaultSessionScope}
            onChange={(e) => setForm((p) => ({ ...p, defaultSessionScope: e.target.value }))}
          >
            <option value="">선택 안함</option>
            <option value="chat">chat</option>
            <option value="workflow">workflow</option>
            <option value="project">project</option>
            <option value="runtime">runtime</option>
          </select>
        </FormField>
        <FormField label="Tool Allowlist (JSON 배열)" hint='["tool_a", "tool_b"] 형식'>
          <textarea
            style={{ ...textareaStyle, minHeight: 80, fontSize: 12 }}
            className="font-mono"
            value={form.defaultToolAllowlist}
            onChange={(e) => setForm((p) => ({ ...p, defaultToolAllowlist: e.target.value }))}
          />
        </FormField>
        <div className="flex gap-2 justify-end mt-2">
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            disabled={createConfig.isPending || updateConfig.isPending || !form.domainApp}
            onClick={handleSave}
          >
            저장
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
