import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { COLORS, FONTS } from "../../theme";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { Modal } from "../../components/common/Modal";
import { FormField, inputStyle } from "../../components/common/FormField";
import { EmptyState } from "../../components/common/EmptyState";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { PageHeader } from "../../components/layout/PageHeader";
import {
  useApps, useCreateApp, useDeleteApp,
  useSuspendApp, useActivateApp,
} from "../../hooks/usePlatform";
import type { App, AppCreateRequest } from "../../types/tenant";

export default function Apps() {
  const navigate = useNavigate();
  const { data: apps = [], isLoading } = useApps();
  const createApp = useCreateApp();
  const deleteApp = useDeleteApp();
  const suspendApp = useSuspendApp();
  const activateApp = useActivateApp();

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState<AppCreateRequest>({
    appId: "", name: "", description: "",
    ownerEmail: "", ownerPassword: "", maxTenants: 100,
  });

  const columns: Column<App>[] = [
    {
      header: "App ID",
      render: (a) => (
        <span
          style={{ fontFamily: FONTS.mono, color: COLORS.accent, cursor: "pointer", textDecoration: "underline" }}
          onClick={() => navigate(`/platform/apps/${a.id}/tenants`)}
        >
          {a.id}
        </span>
      ),
    },
    {
      header: "이름",
      render: (a) => <span style={{ fontWeight: 600 }}>{a.name}</span>,
    },
    {
      header: "설명",
      render: (a) => (
        <span style={{ fontSize: 12, color: COLORS.textMuted }}>
          {a.description || "—"}
        </span>
      ),
    },
    {
      header: "상태",
      render: (a) => (
        <Badge color={a.status === "active" ? "success" : a.status === "suspended" ? "warning" : "danger"}>
          {a.status}
        </Badge>
      ),
      width: "90px",
    },
    {
      header: "DB",
      render: (a) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textDim }}>
          {a.dbName ?? "—"}
        </span>
      ),
    },
    {
      header: "최대 테넌트",
      render: (a) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 12 }}>
          {a.maxTenants ?? 100}
        </span>
      ),
      width: "100px",
    },
    {
      header: "생성일",
      render: (a) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textMuted }}>
          {a.createdAt ? new Date(a.createdAt).toLocaleDateString("ko-KR") : "—"}
        </span>
      ),
      width: "100px",
    },
    {
      header: "액션",
      render: (a) => (
        <div style={{ display: "flex", gap: 4 }}>
          <ActionButton small variant="ghost" onClick={() => navigate(`/platform/apps/${a.id}/tenants`)}>
            테넌트
          </ActionButton>
          {a.status === "active" ? (
            <ActionButton small variant="danger" disabled={suspendApp.isPending} onClick={() => suspendApp.mutate(a.id)}>
              정지
            </ActionButton>
          ) : a.status === "suspended" ? (
            <ActionButton small variant="success" disabled={activateApp.isPending} onClick={() => activateApp.mutate(a.id)}>
              활성화
            </ActionButton>
          ) : null}
          <ActionButton small variant="ghost" onClick={() => deleteApp.mutate(a.id)}>
            삭제
          </ActionButton>
        </div>
      ),
      width: "200px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader
        title="App(소비앱) 관리"
        subtitle="Super Admin — 소비앱 등록/관리 및 하위 테넌트 확인"
        actions={
          <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
            App 생성
          </ActionButton>
        }
      />

      {apps.length === 0 ? (
        <EmptyState
          icon="📱"
          title="등록된 App이 없습니다"
          description="소비앱을 등록하면 App DB가 자동 생성되고, 하위 테넌트를 관리할 수 있습니다"
          action={
            <ActionButton variant="primary" icon="+" onClick={() => setShowModal(true)}>
              첫 App 생성
            </ActionButton>
          }
        />
      ) : (
        <DataTable columns={columns} data={apps} keyExtractor={(a) => a.id} />
      )}

      <Modal open={showModal} onClose={() => setShowModal(false)} title="App(소비앱) 생성">
        <FormField label="App ID" hint="영문, 숫자, 하이픈만 사용 (예: shop-chatbot)">
          <input style={inputStyle} placeholder="my-app" value={form.appId} onChange={(e) => setForm((p) => ({ ...p, appId: e.target.value }))} />
        </FormField>
        <FormField label="App 이름">
          <input style={inputStyle} placeholder="쇼핑몰 AI 챗봇" value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
        </FormField>
        <FormField label="설명">
          <input style={inputStyle} placeholder="App에 대한 간단한 설명" value={form.description} onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))} />
        </FormField>
        <FormField label="소비앱 어드민 이메일">
          <input type="email" style={inputStyle} placeholder="owner@example.com" value={form.ownerEmail} onChange={(e) => setForm((p) => ({ ...p, ownerEmail: e.target.value }))} />
        </FormField>
        <FormField label="소비앱 어드민 비밀번호">
          <input type="password" style={inputStyle} placeholder="초기 비밀번호" value={form.ownerPassword} onChange={(e) => setForm((p) => ({ ...p, ownerPassword: e.target.value }))} />
        </FormField>
        <FormField label="최대 테넌트 수">
          <input type="number" style={inputStyle} value={form.maxTenants} onChange={(e) => setForm((p) => ({ ...p, maxTenants: parseInt(e.target.value) || 100 }))} />
        </FormField>
        <div
          style={{
            padding: "12px 14px",
            borderRadius: 8,
            background: COLORS.accentDim + "20",
            border: `1px solid ${COLORS.accentDim}`,
            fontSize: 12,
            fontFamily: FONTS.mono,
            color: COLORS.textMuted,
            marginBottom: 16,
          }}
        >
          App 생성 시 전용 DB(aimbase_app_&lt;appId&gt;)가 자동 생성되고,
          소비앱 어드민이 하위 테넌트를 셀프서비스로 관리할 수 있습니다.
        </div>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <ActionButton variant="ghost" onClick={() => setShowModal(false)}>취소</ActionButton>
          <ActionButton
            variant="primary"
            icon="📱"
            disabled={createApp.isPending || !form.appId || !form.name || !form.ownerEmail || !form.ownerPassword}
            onClick={() => createApp.mutate(form, { onSuccess: () => { setShowModal(false); setForm({ appId: "", name: "", description: "", ownerEmail: "", ownerPassword: "", maxTenants: 100 }); } })}
          >
            생성
          </ActionButton>
        </div>
      </Modal>
    </div>
  );
}
