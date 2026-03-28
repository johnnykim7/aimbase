import { useState } from "react";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { Modal } from "../../components/common/Modal";
import { FormField, inputStyle, selectStyle } from "../../components/common/FormField";
import { EmptyState } from "../../components/common/EmptyState";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { Page } from "../../components/layout/Page";
import { KeyRound } from "lucide-react";
import { useApiKeys, useCreateApiKey, useRevokeApiKey, useRegenerateApiKey } from "../../hooks/usePlatform";
import { useTenants } from "../../hooks/usePlatform";
import type { ApiKey, CreateApiKeyRequest } from "../../types/tenant";

const DOMAIN_APPS = ["axopm", "chatpilot", "lexflow"];

export default function ApiKeys() {
  const [tenantFilter, setTenantFilter] = useState<string>("");
  const { data: keys = [], isLoading } = useApiKeys(tenantFilter || undefined);
  const { data: tenants = [] } = useTenants();
  const createKey = useCreateApiKey();
  const revokeKey = useRevokeApiKey();
  const regenerateKey = useRegenerateApiKey();

  const [showCreate, setShowCreate] = useState(false);
  const [showResult, setShowResult] = useState(false);
  const [generatedKey, setGeneratedKey] = useState("");
  const [copied, setCopied] = useState(false);
  const [form, setForm] = useState<CreateApiKeyRequest>({ name: "", domainApp: "" });

  const handleCreate = () => {
    createKey.mutate(form, {
      onSuccess: (res) => {
        const apiKey = (res.data.data as any)?.apiKey ?? "";
        setGeneratedKey(apiKey);
        setShowCreate(false);
        setShowResult(true);
        setForm({ name: "", domainApp: "" });
      },
    });
  };

  const handleRegenerate = (id: string) => {
    if (!confirm("기존 키가 폐기되고 새 키가 발급됩니다. 계속하시겠습니까?")) return;
    regenerateKey.mutate(id, {
      onSuccess: (res) => {
        const apiKey = (res.data.data as any)?.apiKey ?? "";
        setGeneratedKey(apiKey);
        setShowResult(true);
      },
    });
  };

  const handleRevoke = (id: string) => {
    if (!confirm("이 API Key를 폐기하시겠습니까? 이 키를 사용하는 모든 연동이 중단됩니다.")) return;
    revokeKey.mutate(id);
  };

  const copyToClipboard = () => {
    navigator.clipboard.writeText(generatedKey);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const columns: Column<ApiKey>[] = [
    {
      header: "이름",
      render: (k) => <span className="font-semibold">{k.name}</span>,
    },
    {
      header: "키 Prefix",
      render: (k) => (
        <span className="font-mono text-xs text-primary">
          {k.keyPrefix}***
        </span>
      ),
      width: "110px",
    },
    {
      header: "제품",
      render: (k) => <Badge color="purple">{k.domainApp}</Badge>,
      width: "100px",
    },
    {
      header: "테넌트",
      render: (k) => {
        if (!k.tenantId) return <span className="text-muted-foreground/40 text-xs">-</span>;
        const t = tenants.find((t) => t.id === k.tenantId);
        return <span className="font-mono text-xs">{t?.name ?? k.tenantId}</span>;
      },
    },
    {
      header: "상태",
      render: (k) => (
        <Badge color={k.isActive ? "success" : "danger"}>
          {k.isActive ? "활성" : "폐기"}
        </Badge>
      ),
      width: "80px",
    },
    {
      header: "마지막 사용",
      render: (k) => (
        <span className="font-mono text-[11px] text-muted-foreground">
          {k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString("ko-KR") : "미사용"}
        </span>
      ),
      width: "150px",
    },
    {
      header: "만료",
      render: (k) => (
        <span className={`font-mono text-[11px] ${k.expiresAt ? "text-warning" : "text-muted-foreground/40"}`}>
          {k.expiresAt ? new Date(k.expiresAt).toLocaleDateString("ko-KR") : "무기한"}
        </span>
      ),
      width: "100px",
    },
    {
      header: "생성일",
      render: (k) => (
        <span className="font-mono text-[11px] text-muted-foreground">
          {new Date(k.createdAt).toLocaleDateString("ko-KR")}
        </span>
      ),
      width: "100px",
    },
    {
      header: "액션",
      render: (k) => (
        <div className="flex gap-1">
          <ActionButton small variant="ghost" onClick={() => handleRegenerate(k.id)}>
            재발급
          </ActionButton>
          <ActionButton small variant="danger" onClick={() => handleRevoke(k.id)}>
            폐기
          </ActionButton>
        </div>
      ),
      width: "140px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={
        <div className="flex gap-2 items-center">
          <select
            style={{ ...selectStyle, width: 160, fontSize: 12, padding: "6px 8px" }}
            value={tenantFilter}
            onChange={(e) => setTenantFilter(e.target.value)}
          >
            <option value="">전체 테넌트</option>
            {tenants.map((t) => (
              <option key={t.id} value={t.id}>{t.name || t.id}</option>
            ))}
          </select>
          <ActionButton variant="primary" icon="+" onClick={() => setShowCreate(true)}>
            키 발급
          </ActionButton>
        </div>
      }
    >

      {keys.length === 0 ? (
        <EmptyState
          icon={<KeyRound className="size-6" />}
          title="발급된 API Key가 없습니다"
          description="외부 시스템 연동을 위한 API Key를 발급합니다"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowCreate(true)}>
              첫 키 발급
            </ActionButton>
          }
        />
      ) : (
        <DataTable columns={columns} data={keys} keyExtractor={(k) => k.id} />
      )}

      {/* 발급 모달 */}
      <Modal open={showCreate} onClose={() => setShowCreate(false)} title="API Key 발급">
        <FormField label="키 이름" hint="용도를 식별할 수 있는 이름">
          <input
            style={inputStyle}
            placeholder="AXOPM CompanyA 연동키"
            value={form.name}
            onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
          />
        </FormField>
        <FormField label="제품 (domainApp)">
          <select
            style={selectStyle}
            value={form.domainApp}
            onChange={(e) => setForm((p) => ({ ...p, domainApp: e.target.value }))}
          >
            <option value="">선택</option>
            {DOMAIN_APPS.map((d) => (
              <option key={d} value={d}>{d}</option>
            ))}
          </select>
        </FormField>
        <FormField label="테넌트 (선택)" hint="생략하면 제품 전용 키 (테넌트 바인딩 없음)">
          <select
            style={selectStyle}
            value={form.tenantId ?? ""}
            onChange={(e) => setForm((p) => ({ ...p, tenantId: e.target.value || undefined }))}
          >
            <option value="">없음 (도메인 전용)</option>
            {tenants.map((t) => (
              <option key={t.id} value={t.id}>{t.name || t.id}</option>
            ))}
          </select>
        </FormField>
        <div className="py-3 px-3.5 rounded-lg bg-primary/10 border border-primary/30 text-xs font-mono text-muted-foreground mb-4 leading-relaxed">
          <div>- 테넌트를 선택하면 이 키로 해당 테넌트 DB에 자동 라우팅됩니다</div>
          <div>- 발급된 키는 <strong>최초 1회만 확인 가능</strong>합니다</div>
        </div>
        <div className="flex gap-2 justify-end">
          <ActionButton variant="ghost" onClick={() => setShowCreate(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="🔑"
            disabled={createKey.isPending || !form.name || !form.domainApp}
            onClick={handleCreate}
          >
            발급
          </ActionButton>
        </div>
      </Modal>

      {/* 키 결과 모달 */}
      <Modal open={showResult} onClose={() => { setShowResult(false); setGeneratedKey(""); setCopied(false); }} title="API Key 발급 완료">
        <div className="p-4 rounded-lg bg-muted border border-border mb-4">
          <div className="text-[11px] text-muted-foreground/40 mb-2">
            아래 키를 안전한 곳에 저장하세요. 이 창을 닫으면 다시 확인할 수 없습니다.
          </div>
          <div className="font-mono text-[13px] text-primary break-all p-3 bg-background rounded-md border border-border">
            {generatedKey}
          </div>
        </div>
        <div className="flex gap-2 justify-end">
          <ActionButton variant="primary" onClick={copyToClipboard}>
            {copied ? "복사됨!" : "클립보드 복사"}
          </ActionButton>
          <ActionButton variant="ghost" onClick={() => { setShowResult(false); setGeneratedKey(""); setCopied(false); }}>
            닫기
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
