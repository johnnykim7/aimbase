import { useState } from "react";
import { COLORS, FONTS } from "../../theme";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { EmptyState } from "../../components/common/EmptyState";
import { FormField, inputStyle } from "../../components/common/FormField";
import { PageHeader } from "../../components/layout/PageHeader";
import { useSubscriptions, useUpdateSubscription } from "../../hooks/usePlatform";
import type { Subscription } from "../../types/tenant";

export default function Subscriptions() {
  const { data: subscriptions = [], isLoading } = useSubscriptions();
  const updateSubscription = useUpdateSubscription();

  const [editing, setEditing] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<Partial<Subscription>>({});

  const startEdit = (sub: Subscription) => {
    setEditing(sub.tenantId);
    setEditForm({ ...sub });
  };

  const saveEdit = () => {
    if (!editing) return;
    updateSubscription.mutate(
      { tenantId: editing, data: editForm },
      { onSuccess: () => setEditing(null) }
    );
  };

  const columns: Column<Subscription>[] = [
    {
      header: "테넌트",
      render: (s) => (
        <div>
          <div style={{ fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text }}>{s.tenantName ?? s.tenantId}</div>
          <div style={{ fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textDim }}>{s.tenantId}</div>
        </div>
      ),
    },
    {
      header: "플랜",
      render: (s) => (
        <Badge color={s.plan === "enterprise" ? "purple" : s.plan === "standard" ? "accent" : "muted"}>
          {s.plan ?? "standard"}
        </Badge>
      ),
      width: "100px",
    },
    {
      header: "월간 토큰 한도",
      render: (s) => (
        <div>
          <div style={{ fontFamily: FONTS.mono, fontSize: 12, color: COLORS.text }}>
            {s.monthlyTokenQuota?.toLocaleString() ?? "무제한"}
          </div>
          {s.usedTokens != null && s.monthlyTokenQuota != null && (
            <div style={{ fontSize: 10, fontFamily: FONTS.mono, color: COLORS.textDim }}>
              사용: {((s.usedTokens / s.monthlyTokenQuota) * 100).toFixed(0)}%
            </div>
          )}
        </div>
      ),
      width: "140px",
    },
    {
      header: "일일 요청 한도",
      render: (s) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 12 }}>
          {s.dailyRequestQuota?.toLocaleString() ?? "무제한"}
        </span>
      ),
      width: "120px",
    },
    {
      header: "최대 연결",
      render: (s) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 12 }}>
          {s.maxConnections ?? "무제한"}
        </span>
      ),
      width: "90px",
    },
    {
      header: "만료일",
      render: (s) => (
        <span style={{ fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textMuted }}>
          {s.expiresAt ? new Date(s.expiresAt).toLocaleDateString("ko-KR") : "—"}
        </span>
      ),
      width: "100px",
    },
    {
      header: "액션",
      render: (s) => (
        <ActionButton small variant="ghost" onClick={() => startEdit(s)}>
          편집
        </ActionButton>
      ),
      width: "70px",
    },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <div>
      <PageHeader title="구독/쿼터 관리" subtitle="Super Admin — 테넌트별 사용 한도 설정" />

      {subscriptions.length === 0 ? (
        <EmptyState icon="💳" title="구독 정보가 없습니다" description="테넌트를 먼저 생성하세요" />
      ) : (
        <DataTable columns={columns} data={subscriptions} keyExtractor={(s) => s.tenantId} />
      )}

      {/* Inline Edit Panel */}
      {editing && editForm && (
        <div
          style={{
            marginTop: 20,
            background: COLORS.surface,
            border: `1px solid ${COLORS.accent}40`,
            borderRadius: 12,
            padding: 24,
          }}
        >
          <div style={{ fontSize: 14, fontWeight: 600, fontFamily: FONTS.sans, color: COLORS.text, marginBottom: 20 }}>
            쿼터 편집 — {editForm.tenantName ?? editing}
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 16 }}>
            <FormField label="월간 토큰 한도">
              <input
                type="number"
                style={inputStyle}
                value={editForm.monthlyTokenQuota ?? ""}
                onChange={(e) => setEditForm((p) => ({ ...p, monthlyTokenQuota: Number(e.target.value) }))}
              />
            </FormField>
            <FormField label="일일 요청 한도">
              <input
                type="number"
                style={inputStyle}
                value={editForm.dailyRequestQuota ?? ""}
                onChange={(e) => setEditForm((p) => ({ ...p, dailyRequestQuota: Number(e.target.value) }))}
              />
            </FormField>
            <FormField label="최대 연결 수">
              <input
                type="number"
                style={inputStyle}
                value={editForm.maxConnections ?? ""}
                onChange={(e) => setEditForm((p) => ({ ...p, maxConnections: Number(e.target.value) }))}
              />
            </FormField>
          </div>
          <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
            <ActionButton variant="ghost" onClick={() => setEditing(null)}>취소</ActionButton>
            <ActionButton variant="primary" icon="💾" disabled={updateSubscription.isPending} onClick={saveEdit}>
              저장
            </ActionButton>
          </div>
        </div>
      )}
    </div>
  );
}
