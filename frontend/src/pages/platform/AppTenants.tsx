import { useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { COLORS, FONTS } from "../../theme";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { Modal } from "../../components/common/Modal";
import { FormField, inputStyle, selectStyle } from "../../components/common/FormField";
import { EmptyState } from "../../components/common/EmptyState";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { PageHeader } from "../../components/layout/PageHeader";
import { useAppTenants, useAppCreateTenant } from "../../hooks/usePlatform";
import { platformApi } from "../../api/platform";
import { useQueryClient } from "@tanstack/react-query";
import type { Tenant, AppTenantCreateRequest } from "../../types/tenant";

export default function AppTenants() {
  const { appId } = useParams<{ appId: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: tenants = [], isLoading } = useAppTenants(appId!);
  const createTenant = useAppCreateTenant(appId!);

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState<AppTenantCreateRequest>({
    tenantId: "", name: "", adminEmail: "", initialAdminPassword: "", plan: "free",
  });

  const handleSuspend = async (tenantId: string) => {
    await platformApi.appSuspendTenant(appId!, tenantId);
    qc.invalidateQueries({ queryKey: ["appTenants", appId] });
  };

  const handleActivate = async (tenantId: string) => {
    await platformApi.appActivateTenant(appId!, tenantId);
    qc.invalidateQueries({ queryKey: ["appTenants", appId] });
  };

  const handleDelete = async (tenantId: string) => {
    await platformApi.appDeleteTenant(appId!, tenantId);
    qc.invalidateQueries({ queryKey: ["appTenants", appId] });
  };

  const columns: Column<Tenant>[] = [
    {
      header: "테넌트 ID",
      render: (t) => <span style={{ fontFamily: FONTS.mono, color: COLORS.accent }}>{t.id}</span>,
    },
    {
      header: "이름",
      render: (t) => <span style={{ fontWeight: 600 }}>{t.name}</span>,
    },
    {
      header: "관리자",
      render: (t) => (
        <span style={{ fontSize: 12, color: COLORS.textMuted }}>
          {t.adminEmail || "—"}
        </span>
      ),
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
        <span style={{ fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textDim }}>
          {t.dbName ?? "—"}
        </span>
      ),
    },
    {
      header: "생성일",
      render: (t) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textMuted }}>
          {t.createdAt ? new Date(t.createdAt).toLocaleDateString("ko-KR") : "—"}
        </span>
      ),
      width: "100px",
    },
    {
      header: "액션",
      render: (t) => (
        <div style={{ display: "flex", gap: 4 }}>
          {t.status === "active" ? (
            <ActionButton small variant="danger" onClick={() => handleSuspend(t.id)}>
              정지
            </ActionButton>
          ) : t.status === "suspended" ? (
            <ActionButton small variant="success" onClick={() => handleActivate(t.id)}>
              활성화
            </ActionButton>
          ) : null}
          <ActionButton small variant="ghost" onClick={() => handleDelete(t.id)}>
            삭제
          </ActionButton>
        </div>
      ),
      width: "150px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title={`App [${appId}] 테넌트 관리`}
        subtitle="소비앱 하위 테넌트 목록 및 셀프서비스 관리"
        actions={
          <div style={{ display: "flex", gap: 8 }}>
            <ActionButton variant="ghost" onClick={() => navigate("/platform/apps")}>
              App 목록
            </ActionButton>
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              테넌트 생성
            </ActionButton>
          </div>
        }
      />

      {tenants.length === 0 ? (
        <EmptyState
          icon="🏢"
          title="하위 테넌트가 없습니다"
          description={`App [${appId}]에 테넌트를 추가하세요. DB가 자동 프로비저닝됩니다.`}
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
        <FormField label="테넌트 ID" hint="영문, 숫자, 하이픈만 (예: store-a)">
          <input style={inputStyle} placeholder="tenant-id" value={form.tenantId} onChange={(e) => setForm((p) => ({ ...p, tenantId: e.target.value }))} />
        </FormField>
        <FormField label="테넌트 이름">
          <input style={inputStyle} placeholder="입점사 A" value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="관리자 이메일">
          <input type="email" style={inputStyle} placeholder="admin@store-a.com" value={form.adminEmail} onChange={(e) => setForm((p) => ({ ...p, adminEmail: e.target.value }))} />
        </FormField>
        <FormField label="관리자 비밀번호">
          <input type="password" style={inputStyle} placeholder="초기 비밀번호" value={form.initialAdminPassword} onChange={(e) => setForm((p) => ({ ...p, initialAdminPassword: e.target.value }))} />
        </FormField>
        <FormField label="플랜">
          <select style={selectStyle} value={form.plan} onChange={(e) => setForm((p) => ({ ...p, plan: e.target.value }))}>
            <option value="free">Free</option>
            <option value="starter">Starter</option>
            <option value="pro">Pro</option>
            <option value="enterprise">Enterprise</option>
          </select>
        </FormField>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 16 }}>
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="🏢"
            disabled={createTenant.isPending || !form.tenantId || !form.name || !form.adminEmail || !form.initialAdminPassword}
            onClick={() => createTenant.mutate(form, { onSuccess: () => { setShowModal(false); setForm({ tenantId: "", name: "", adminEmail: "", initialAdminPassword: "", plan: "free" }); } })}
          >
            생성
          </ActionButton>
        </div>
      </Modal>
    </div>
  );
}
