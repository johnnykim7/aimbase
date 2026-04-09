import { useState } from "react";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { Modal } from "../../components/common/Modal";
import { FormField, inputStyle, selectStyle } from "../../components/common/FormField";
import { EmptyState } from "../../components/common/EmptyState";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { Page } from "../../components/layout/Page";
import { Building2 } from "lucide-react";
import {
  useTenants, useCreateTenant, useDeleteTenant,
  useSuspendTenant, useActivateTenant,
} from "../../hooks/usePlatform";
import type { Tenant, TenantRequest } from "../../types/tenant";

export default function Tenants() {
  const [domainAppFilter] = useState<string>("");
  const { data: tenants = [], isLoading } = useTenants(domainAppFilter || undefined);
  const createTenant = useCreateTenant();
  const deleteTenant = useDeleteTenant();
  const suspendTenant = useSuspendTenant();
  const activateTenant = useActivateTenant();

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState<TenantRequest>({ id: "", name: "", adminEmail: "", plan: "standard", domainApp: "" });

  const columns: Column<Tenant>[] = [
    {
      header: "테넌트",
      render: (t) => <span className="font-mono text-primary">{t.name || t.id}</span>,
    },
    {
      header: "이름",
      render: (t) => <span className="font-semibold">{t.name}</span>,
    },
    {
      header: "플랜",
      render: (t) => (
        <Badge color={t.plan === "enterprise" ? "purple" : t.plan === "standard" ? "accent" : "muted"}>
          {t.plan ?? "standard"}
        </Badge>
      ),
      width: "100px",
    },
    {
      header: "상태",
      render: (t) => (
        <Badge color={t.status === "active" ? "success" : t.status === "suspended" ? "warning" : "danger"}>
          {t.status}
        </Badge>
      ),
      width: "90px",
    },
    {
      header: "DB",
      render: (t) => (
        <span className="font-mono text-[11px] text-muted-foreground/40">
          {t.dbName ?? "—"}
        </span>
      ),
    },
    {
      header: "생성일",
      render: (t) => (
        <span className="font-mono text-[11px] text-muted-foreground">
          {t.createdAt ? new Date(t.createdAt).toLocaleDateString("ko-KR") : "—"}
        </span>
      ),
      width: "100px",
    },
    {
      header: "액션",
      render: (t) => (
        <div className="flex gap-1">
          {t.status === "active" ? (
            <ActionButton small variant="danger" disabled={suspendTenant.isPending} onClick={() => suspendTenant.mutate(t.id)}>
              정지
            </ActionButton>
          ) : t.status === "suspended" ? (
            <ActionButton small variant="success" disabled={activateTenant.isPending} onClick={() => activateTenant.mutate(t.id)}>
              활성화
            </ActionButton>
          ) : null}
          <ActionButton small variant="ghost" onClick={() => deleteTenant.mutate(t.id)}>
            삭제
          </ActionButton>
        </div>
      ),
      width: "150px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page
      actions={
        <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
          테넌트 생성
        </ActionButton>
      }
    >

      {tenants.length === 0 ? (
        <EmptyState
          icon={<Building2 className="size-6" />}
          title="등록된 테넌트가 없습니다"
          description="플랫폼에 새로운 테넌트를 추가하고 DB를 자동 프로비저닝합니다"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              첫 테넌트 생성
            </ActionButton>
          }
        />
      ) : (
        <DataTable columns={columns} data={tenants} keyExtractor={(t) => t.id} />
      )}

      <Modal open={showModal} onClose={() => setShowModal(false)} title="테넌트 생성">
        <FormField label="테넌트 ID" hint="서브도메인 및 DB 이름에 사용됩니다 (영문, 숫자, 하이픈만)">
          <input style={inputStyle} placeholder="my-company" value={form.id} onChange={(e) => setForm((p) => ({ ...p, id: e.target.value }))} />
        </FormField>
        <FormField label="테넌트 이름">
          <input style={inputStyle} placeholder="My Company" value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="관리자 이메일">
          <input type="email" style={inputStyle} placeholder="admin@mycompany.com" value={form.adminEmail} onChange={(e) => setForm((p) => ({ ...p, adminEmail: e.target.value }))} />
        </FormField>
        <FormField label="플랜">
          <select style={selectStyle} value={form.plan} onChange={(e) => setForm((p) => ({ ...p, plan: e.target.value }))}>
            <option value="free">Free</option>
            <option value="standard">Standard</option>
            <option value="enterprise">Enterprise</option>
          </select>
        </FormField>
        <div className="py-3 px-3.5 rounded-lg bg-primary/10 border border-primary/30 text-xs font-mono text-muted-foreground mb-4">
          ℹ️ 테넌트 생성 시 PostgreSQL DB가 자동 생성되고 Flyway 마이그레이션이 실행됩니다.
        </div>
        <div className="flex gap-2 justify-end">
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="🏢"
            disabled={createTenant.isPending}
            onClick={() => createTenant.mutate(form, { onSuccess: () => setShowModal(false) })}
          >
            생성
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
