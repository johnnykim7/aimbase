import { useState } from "react";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { Modal } from "../../components/common/Modal";
import { FormField, inputStyle } from "../../components/common/FormField";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../../components/ui/select";
import { EmptyState } from "../../components/common/EmptyState";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { Page } from "../../components/layout/Page";
import { Building2 } from "lucide-react";
import {
  useTenants, useCreateTenant, useDeleteTenant,
  useSuspendTenant, useActivateTenant, useImpersonateTenant,
  usePlans, useSubscriptions,
} from "../../hooks/usePlatform";
import { enterImpersonation } from "../../lib/impersonation";
import type { Tenant, TenantRequest } from "../../types/tenant";

export default function Tenants() {
  const [domainAppFilter] = useState<string>("");
  const { data: tenants = [], isLoading } = useTenants(domainAppFilter || undefined);
  const createTenant = useCreateTenant();
  const deleteTenant = useDeleteTenant();
  const suspendTenant = useSuspendTenant();
  const activateTenant = useActivateTenant();
  const impersonate = useImpersonateTenant();
  // CR-191: 테넌트 목록에는 플랜이 없어 구독 목록에서 찾는다. 생성 창의 선택지와 처음 선택은 플랜 목록을 따른다.
  const { data: subscriptions = [] } = useSubscriptions();
  const { data: planCatalog, isError: plansFailed } = usePlans();
  const planOf = new Map(subscriptions.map((s) => [s.tenantId, s.plan]));

  // CR-096: 대상 테넌트로 진입 — 토큰 교체 후 루트로 이동(전체 화면이 그 테넌트로 동작)
  const handleEnter = (id: string, name: string) => {
    impersonate.mutate(id, {
      onSuccess: (res) => {
        enterImpersonation(res.access_token, res.tenant_id, res.tenant_name || name);
        window.location.href = "/";
      },
    });
  };

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState<TenantRequest>({ id: "", name: "", adminEmail: "", plan: "", domainApp: "" });
  const selectedPlan = form.plan || planCatalog?.defaultPlan || "";

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
        <Badge color="muted">{planOf.get(t.id) ?? "-"}</Badge>
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
          {/* CR-096: 임퍼소네이션 — 대상 테넌트로 들어가서 화면 전체를 본다 */}
          {t.status === "active" && (
            <ActionButton
              small
              variant="primary"
              disabled={impersonate.isPending}
              onClick={() => handleEnter(t.id, t.name || t.id)}
            >
              들어가기
            </ActionButton>
          )}
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
          <Select value={selectedPlan} onValueChange={(v) => setForm((p) => ({ ...p, plan: v }))} disabled={plansFailed}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(planCatalog?.plans ?? []).map((plan) => (
                <SelectItem key={plan.code} value={plan.code}>{plan.code}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          {plansFailed && <div className="text-[11px] text-destructive mt-1">플랜 목록을 읽지 못했습니다.</div>}
        </FormField>
        <div className="py-3 px-3.5 rounded-lg bg-primary/10 border border-primary/30 text-xs font-mono text-muted-foreground mb-4">
          ℹ️ 테넌트 생성 시 PostgreSQL DB가 자동 생성되고 Flyway 마이그레이션이 실행됩니다.
        </div>
        <div className="flex gap-2 justify-end">
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="🏢"
            disabled={createTenant.isPending || plansFailed || !selectedPlan}
            onClick={() => createTenant.mutate({ ...form, plan: selectedPlan }, { onSuccess: () => setShowModal(false) })}
          >
            생성
          </ActionButton>
        </div>
      </Modal>
    </Page>
  );
}
