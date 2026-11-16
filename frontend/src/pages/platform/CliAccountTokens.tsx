import { useMemo, useState } from "react";
import { KeySquare } from "lucide-react";
import { Badge } from "../../components/common/Badge";
import { DataTable, type Column } from "../../components/common/DataTable";
import { EmptyState } from "../../components/common/EmptyState";
import { LoadingSpinner } from "../../components/common/LoadingSpinner";
import { Modal } from "../../components/common/Modal";
import { Input } from "../../components/ui/input";
import { Page } from "../../components/layout/Page";
import { AccountTokenField } from "../../components/connections/AccountTokenField";
import { TokenRecipientsCard } from "../../components/connections/TokenRecipientsCard";
import { TokenStateChips, type ChipFilter } from "../../components/connections/TokenStateChips";
import {
  TokenBadge,
  dateOf,
  folderName,
  minuteOf,
  presenceState,
  stateRank,
  type ChipState,
} from "../../components/connections/accountTokenState";
import { usePlatformCliAccountTokens, usePutPlatformAccountToken } from "../../hooks/useCliAccountTokens";
import { usePlatformSettings, useUpdatePlatformSettings } from "../../hooks/usePlatformSettings";
import type { AccountTokenRow, TokenRecipients } from "../../types/cliAccountToken";

// 서버 CliTokenExpiryJob 이 읽는 런타임 설정. Aimbase 가 빌려준 계정의 만료 알림이 이 수신자에게 간다.
const SETTING_CATEGORY = "runner";
const KEY_EMAILS = "runner.token_expiry.emails";
const KEY_PHONES = "runner.token_expiry.phones";

// 칩은 토큰 상태로 모든 줄을 가르고, Aimbase 관리는 그 위에 겹쳐 거르는 칩이다. 빌려준 계정이 기한 지남에서 빠지지 않게 한다.
const PLATFORM_CHIPS: ChipState[] = ["expired", "missing", "soon", "ok", "platform"];

const rowKey = (r: AccountTokenRow) => `${r.tenant_id}|${r.agent_name}|${r.config_dir}`;
const splitSetting = (v: string | undefined) => (v ?? "").split(",").map((x) => x.trim()).filter(Boolean);

export default function CliAccountTokens() {
  const { data: rows = [], isLoading, isError } = usePlatformCliAccountTokens();
  const settings = usePlatformSettings(SETTING_CATEGORY);
  const updateSettings = useUpdatePlatformSettings();
  const putToken = usePutPlatformAccountToken();

  const [query, setQuery] = useState("");
  const [chip, setChip] = useState<ChipFilter>("all");
  const [openKey, setOpenKey] = useState<string | null>(null);

  const recipients: TokenRecipients | undefined = useMemo(() => {
    const items = settings.data?.[SETTING_CATEGORY];
    if (!items) return undefined;
    const value = (key: string) => items.find((i) => i.key === key)?.value;
    return { emails: splitSetting(value(KEY_EMAILS)), phones: splitSetting(value(KEY_PHONES)) };
  }, [settings.data]);

  const saveRecipients = (next: TokenRecipients) =>
    updateSettings.mutateAsync([
      { key: KEY_EMAILS, value: next.emails.join(",") },
      { key: KEY_PHONES, value: next.phones.join(",") },
    ]);

  const searched = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) =>
      [r.tenant_name, r.tenant_id, r.agent_name, r.config_dir].some((v) => (v ?? "").toLowerCase().includes(q)),
    );
  }, [rows, query]);

  const counts = useMemo(() => {
    const c: Partial<Record<ChipState, number>> = {};
    for (const r of searched) {
      const s = presenceState(r);
      c[s] = (c[s] ?? 0) + 1;
      if (r.managed_by === "platform") c.platform = (c.platform ?? 0) + 1;
    }
    return c;
  }, [searched]);

  const shown = useMemo(
    () =>
      searched
        .filter((r) => chip === "all" || (chip === "platform" ? r.managed_by === "platform" : presenceState(r) === chip))
        .sort(
          (a, b) =>
            stateRank(presenceState(a)) - stateRank(presenceState(b)) ||
            (a.tenant_name ?? "").localeCompare(b.tenant_name ?? "") ||
            a.agent_name.localeCompare(b.agent_name) ||
            a.config_dir.localeCompare(b.config_dir),
        ),
    [searched, chip],
  );

  const openRow = rows.find((r) => rowKey(r) === openKey);

  const columns: Column<AccountTokenRow>[] = [
    { header: "테넌트", render: (r) => <span className="font-semibold">{r.tenant_name ?? r.tenant_id}</span> },
    { header: "실행기", render: (r) => <span className="font-mono text-xs">{r.agent_name}</span> },
    {
      header: "계정 폴더",
      render: (r) => (
        <span className="font-mono text-xs" title={r.config_dir}>
          {folderName(r.config_dir)}
        </span>
      ),
    },
    { header: "연결", width: "70px", render: (r) => <span className="text-xs">{r.connection_ids.length}개</span> },
    {
      header: "관리",
      width: "90px",
      render: (r) => <Badge color={r.managed_by === "platform" ? "accent" : "muted"}>{r.managed_by === "platform" ? "Aimbase" : "테넌트"}</Badge>,
    },
    {
      header: "1년 토큰",
      render: (r) => (
        <div>
          <TokenBadge row={r} platformAsGroup={false} />
          {/* 이 목록은 실행기에 묻지 않고 마지막으로 확인해 둔 값을 보이므로 줄마다 그 시각을 붙인다. */}
          {r.reachable ? (
            <div className="mt-1 text-[11px] text-muted-foreground">{minuteOf(r.checked_at)} 확인</div>
          ) : (
            <div className="mt-1 text-[11px] text-warning">실행기가 꺼져 있어 {minuteOf(r.checked_at)} 에 마지막으로 확인한 값입니다.</div>
          )}
        </div>
      ),
    },
    { header: "기한", width: "110px", render: (r) => <span className="text-xs text-muted-foreground">{dateOf(r.expires_at)}</span> },
  ];

  if (isLoading) return <LoadingSpinner fullPage />;

  return (
    <Page>
      <TokenRecipientsCard
        description="Aimbase 가 빌려준 계정의 1년 토큰 기한이 다가오면 이 수신자에게 이메일과 문자가 갑니다."
        recipients={recipients}
        loadError={settings.isError}
        onSave={saveRecipients}
      />

      {isError ? (
        <EmptyState icon={<KeySquare className="size-6" />} title="실행기 계정 토큰을 불러오지 못했습니다." description="잠시 뒤 다시 열어 주세요." />
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<KeySquare className="size-6" />}
          title="서버 실행기를 쓰는 연결이 없습니다."
          description="테넌트가 Claude CLI 연결에 실행기를 지정하면 여기에 보입니다."
        />
      ) : (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-3">
            <Input
              className="w-72"
              placeholder="테넌트, 실행기, 계정 폴더로 찾기"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <TokenStateChips states={PLATFORM_CHIPS} counts={counts} total={searched.length} selected={chip} onSelect={setChip} />
            <span className="ml-auto text-xs text-muted-foreground">{shown.length}건</span>
          </div>
          <DataTable
            columns={columns}
            data={shown}
            keyExtractor={rowKey}
            onRowClick={(r) => setOpenKey(rowKey(r))}
            emptyMessage="조건에 맞는 계정 폴더가 없습니다."
          />
        </>
      )}

      <Modal open={!!openRow} onClose={() => setOpenKey(null)} title="1년 토큰">
        {openRow && (
          <>
            <div className="mb-3 text-xs text-muted-foreground">테넌트 {openRow.tenant_name ?? openRow.tenant_id}</div>
            <AccountTokenField
              key={openKey ?? ""}
              row={openRow}
              platformView
              onSubmit={(token) => putToken.mutateAsync({ row: openRow, token })}
            />
          </>
        )}
      </Modal>
    </Page>
  );
}
