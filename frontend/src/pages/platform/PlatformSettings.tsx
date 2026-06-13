import { useState } from "react";
import { Page } from "../../components/layout/Page";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { ActionButton } from "../../components/common/ActionButton";
import { Settings, RotateCcw, Save } from "lucide-react";
import { usePlatformSettings, useUpdatePlatformSettings, useEvictSettingsCache } from "../../hooks/usePlatformSettings";
import { useTenants } from "../../hooks/usePlatform";
import { Checkbox } from "../../components/ui/checkbox";
import type { SettingItem } from "../../api/platform";

/** anthropic-cli 허용 테넌트 화이트리스트 키 — 텍스트 대신 멀티셀렉트로 편집 */
const TENANT_WHITELIST_KEY = "llm.anthropic-cli.enabled-tenants";

/**
 * 테넌트 화이트리스트 전용 입력 — "전체 허용(*)" 토글 + 테넌트 멀티셀렉트(name 표시 / id 저장).
 * 저장값: "*" 또는 쉼표 구분 tenant id 목록.
 */
function TenantWhitelistInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { data: tenants = [] } = useTenants();
  const isAll = value.trim() === "*";
  const selected = isAll ? [] : value.split(",").map((s) => s.trim()).filter(Boolean);

  const toggle = (id: string) => {
    const next = selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id];
    onChange(next.join(","));
  };

  return (
    <div className="flex flex-col gap-2 items-end max-w-md">
      <label className="flex items-center gap-2 text-sm cursor-pointer">
        <Checkbox size="sm" checked={isAll} onCheckedChange={(c) => onChange(c === true ? "*" : "")} />
        전체 허용 (*)
      </label>
      {!isAll && (
        <div className="flex flex-wrap gap-1.5 justify-end">
          {tenants.map((t) => {
            const on = selected.includes(t.id);
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => toggle(t.id)}
                title={t.id}
                className={`py-1 px-2.5 rounded-lg border text-xs cursor-pointer transition-all ${
                  on ? "border-primary bg-primary/10 text-primary" : "border-border bg-accent text-muted-foreground"
                }`}
              >
                {t.name}
              </button>
            );
          })}
          {tenants.length === 0 && <span className="text-xs text-muted-foreground">테넌트 목록 없음</span>}
        </div>
      )}
    </div>
  );
}

const CATEGORY_LABELS: Record<string, { label: string; description: string }> = {
  orchestrator: { label: "Orchestrator", description: "도구 루프, 토큰 제한, 결과 축약 설정" },
  session: { label: "Session", description: "세션 TTL, 메시지 수/크기 제한" },
  compaction: { label: "Compaction", description: "컨텍스트 압축 임계값 (% 단위)" },
  llm: { label: "LLM", description: "프로바이더/어댑터 정책 — Claude CLI 어댑터 허용 테넌트 등" },
};

export default function PlatformSettings() {
  const { data: settings, isLoading } = usePlatformSettings();
  const updateMutation = useUpdatePlatformSettings();
  const evictMutation = useEvictSettingsCache();
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);

  if (isLoading) return <LoadingSpinner />;

  const handleChange = (key: string, value: string) => {
    setEdits((prev) => ({ ...prev, [key]: value }));
    setSaved(false);
  };

  const handleSave = () => {
    const updates = Object.entries(edits).map(([key, value]) => ({ key, value }));
    if (updates.length === 0) return;
    updateMutation.mutate(updates, {
      onSuccess: () => {
        setEdits({});
        setSaved(true);
        setTimeout(() => setSaved(false), 2000);
      },
    });
  };

  const handleEvict = () => {
    evictMutation.mutate();
  };

  const hasChanges = Object.keys(edits).length > 0;

  return (
    <Page
      actions={
        <div className="flex gap-2">
          <ActionButton
            icon={<RotateCcw className="w-4 h-4" />}
            onClick={handleEvict}
            variant="ghost"
          >
            Cache Evict
          </ActionButton>
          <ActionButton
            icon={<Save className="w-4 h-4" />}
            onClick={handleSave}
            disabled={!hasChanges}
          >
            {saved ? "Saved!" : "Save Changes"}
          </ActionButton>
        </div>
      }
    >
      <div className="flex items-center gap-2 mb-6">
        <Settings className="w-5 h-5 text-muted-foreground" />
        <h2 className="text-lg font-semibold">Platform Runtime Settings</h2>
      </div>

      <div className="grid gap-6">
        {Object.entries(CATEGORY_LABELS).map(([category, meta]) => {
          const items = settings?.[category] ?? [];
          if (items.length === 0) return null;

          return (
            <Card key={category}>
              <CardHeader>
                <CardTitle>{meta.label}</CardTitle>
                <p className="text-sm text-muted-foreground">{meta.description}</p>
              </CardHeader>
              <CardContent>
                <div className="divide-y divide-border">
                  {items.map((item: SettingItem) => {
                    const shortKey = item.key.replace(`${category}.`, "");
                    const currentValue = edits[item.key] ?? item.value;
                    const isEdited = item.key in edits;

                    return (
                      <div
                        key={item.key}
                        className="flex items-center justify-between py-3 gap-4"
                      >
                        <div className="flex-1 min-w-0">
                          <div className="font-mono text-sm">{shortKey}</div>
                          {item.description && (
                            <div className="text-xs text-muted-foreground mt-0.5">
                              {item.description}
                            </div>
                          )}
                        </div>
                        <div className="flex items-center gap-2">
                          {item.key === TENANT_WHITELIST_KEY ? (
                            <TenantWhitelistInput
                              value={currentValue}
                              onChange={(v) => handleChange(item.key, v)}
                            />
                          ) : (
                            <input
                              type="text"
                              value={currentValue}
                              onChange={(e) => handleChange(item.key, e.target.value)}
                              className={`w-32 px-2 py-1 text-sm text-right border rounded-md bg-background ${
                                isEdited
                                  ? "border-primary ring-1 ring-primary/20"
                                  : "border-border"
                              }`}
                            />
                          )}
                          {isEdited && (
                            <button
                              onClick={() => {
                                setEdits((prev) => {
                                  const next = { ...prev };
                                  delete next[item.key];
                                  return next;
                                });
                              }}
                              className="text-xs text-muted-foreground hover:text-foreground"
                              title="Reset to saved value"
                            >
                              <RotateCcw className="w-3.5 h-3.5" />
                            </button>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>
    </Page>
  );
}
