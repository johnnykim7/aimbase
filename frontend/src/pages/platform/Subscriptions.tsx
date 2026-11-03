import { useState } from "react";
import { Badge } from "../../components/common/Badge";
import { ActionButton } from "../../components/common/ActionButton";
import { DataTable, type Column } from "../../components/common/DataTable";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { EmptyState } from "../../components/common/EmptyState";
import { FormField } from "../../components/common/FormField";
import { Input } from "../../components/ui/input";
import { Page } from "../../components/layout/Page";
import { CreditCard } from "lucide-react";
import { useSubscriptions, useUpdateSubscription } from "../../hooks/usePlatform";
import type { Subscription } from "../../types/tenant";

type QuotaKey = "llmMonthlyTokenQuota" | "apiRpmLimit" | "maxConnections";
type EditForm = Record<QuotaKey, string> & { tenantName?: string };

const QUOTA_FIELDS: { key: QuotaKey; label: string; hint?: string }[] = [
  { key: "llmMonthlyTokenQuota", label: "월간 토큰 한도" },
  { key: "apiRpmLimit", label: "분당 요청 한도", hint: "저장하면 다음 요청부터 적용됩니다. 0 은 무제한입니다." },
  { key: "maxConnections", label: "최대 연결 수" },
];

const isWholeNumber = (value: string) => /^\d+$/.test(value);

export default function Subscriptions() {
  const { data: subscriptions = [], isLoading } = useSubscriptions();
  const updateSubscription = useUpdateSubscription();

  const [editing, setEditing] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);

  const startEdit = (sub: Subscription) => {
    updateSubscription.reset();
    setEditing(sub.tenantId);
    setEditForm({
      tenantName: sub.tenantName,
      llmMonthlyTokenQuota: String(sub.llmMonthlyTokenQuota),
      apiRpmLimit: String(sub.apiRpmLimit),
      maxConnections: String(sub.maxConnections),
    });
  };

  const formIsValid = editForm != null && QUOTA_FIELDS.every(({ key }) => isWholeNumber(editForm[key]));

  const saveEdit = () => {
    if (!editing || !editForm || !formIsValid) return;
    updateSubscription.mutate(
      {
        tenantId: editing,
        data: {
          llmMonthlyTokenQuota: Number(editForm.llmMonthlyTokenQuota),
          apiRpmLimit: Number(editForm.apiRpmLimit),
          maxConnections: Number(editForm.maxConnections),
        },
      },
      { onSuccess: () => setEditing(null) }
    );
  };

  const columns: Column<Subscription>[] = [
    {
      header: "테넌트",
      render: (s) => (
        <div>
          <div className="font-semibold text-foreground">{s.tenantName ?? s.tenantId}</div>
          <div className="font-mono text-[11px] text-muted-foreground/40">{s.tenantId}</div>
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
        <span className="font-mono text-xs text-foreground">
          {s.llmMonthlyTokenQuota.toLocaleString()}
        </span>
      ),
      width: "140px",
    },
    {
      header: "분당 요청 한도",
      render: (s) => (
        <span className="font-mono text-xs">
          {s.apiRpmLimit <= 0 ? "무제한" : s.apiRpmLimit.toLocaleString()}
        </span>
      ),
      width: "120px",
    },
    {
      header: "최대 연결",
      render: (s) => (
        <span className="font-mono text-xs">
          {s.maxConnections}
        </span>
      ),
      width: "90px",
    },
    {
      header: "만료일",
      render: (s) => (
        <span className="font-mono text-[11px] text-muted-foreground">
          {s.validUntil ? new Date(s.validUntil).toLocaleDateString("ko-KR") : "-"}
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
    <Page>

      {subscriptions.length === 0 ? (
        <EmptyState icon={<CreditCard className="size-6" />} title="구독 정보가 없습니다" description="테넌트를 먼저 생성하세요" />
      ) : (
        <DataTable columns={columns} data={subscriptions} keyExtractor={(s) => s.tenantId} />
      )}

      {/* Inline Edit Panel */}
      {editing && editForm && (
        <div className="mt-5 bg-card border border-primary/25 rounded-xl p-6">
          <div className="text-sm font-semibold text-foreground mb-5">
            쿼터 편집 — {editForm.tenantName ?? editing}
          </div>
          <div className="grid grid-cols-3 gap-4">
            {QUOTA_FIELDS.map(({ key, label, hint }) => {
              const invalid = !isWholeNumber(editForm[key]);
              return (
                <FormField key={key} label={label} hint={hint}>
                  <Input
                    type="number"
                    min={0}
                    step={1}
                    aria-label={label}
                    aria-invalid={invalid}
                    value={editForm[key]}
                    onChange={(e) => setEditForm((p) => p && { ...p, [key]: e.target.value })}
                  />
                  {invalid && (
                    <div className="text-[11px] text-destructive mt-1">0 이상의 정수를 입력하세요.</div>
                  )}
                </FormField>
              );
            })}
          </div>
          {updateSubscription.isError && (
            <div className="text-xs text-destructive mb-3">
              저장하지 못했습니다: {updateSubscription.error.message}
            </div>
          )}
          <div className="flex gap-2 justify-end mt-2">
            <ActionButton variant="ghost" onClick={() => setEditing(null)}>취소</ActionButton>
            <ActionButton variant="primary" icon="💾" disabled={updateSubscription.isPending || !formIsValid} onClick={saveEdit}>
              저장
            </ActionButton>
          </div>
        </div>
      )}
    </Page>
  );
}
