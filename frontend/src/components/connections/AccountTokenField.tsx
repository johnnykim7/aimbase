import { useState } from "react";
import { cn } from "@/lib/utils";
import { Input } from "../ui/input";
import { Button } from "../ui/button";
import type { AccountTokenRow } from "../../types/cliAccountToken";
import { TokenBadge, dateOf, folderName, minuteOf, putErrorMessage } from "./accountTokenState";

interface AccountTokenFieldProps {
  row: AccountTokenRow | undefined;
  loading?: boolean;
  loadError?: boolean;
  /** 플랫폼 화면이면 Aimbase 관리 계정도 넣는다. 연결 관리 화면은 그 계정에 입력을 두지 않는다. */
  platformView: boolean;
  /** 연결 수정 창에서만 보인다. 그 창의 「수정」 과 따로 들어간다는 안내다. */
  separateFromEdit?: boolean;
  onSubmit: (token: string) => Promise<AccountTokenRow>;
}

type Notice = { kind: "info" | "ok" | "error"; text: string } | null;

/**
 * CR-196: 계정 폴더 하나의 1년 토큰 칸. 값은 이 칸의 화면 상태에만 두고, 창이 닫혀 칸이 사라지면 함께 사라진다.
 */
export function AccountTokenField({ row, loading, loadError, platformView, separateFromEdit, onSubmit }: AccountTokenFieldProps) {
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);

  const box = "mb-4 rounded-lg border border-border p-3.5 text-[13px]";
  if (loading) return <div className={cn(box, "text-muted-foreground text-xs")}>1년 토큰 상태를 불러오는 중입니다.</div>;
  if (loadError || !row) return <div className={cn(box, "text-muted-foreground text-xs")}>1년 토큰 상태를 불러오지 못했습니다.</div>;

  const locked = !platformView && row.managed_by === "platform";
  // 터미널에서 복사하면 줄바꿈이 끼기 쉽다. 토큰에는 빈칸이 없으므로 모두 걷어 낸다.
  const token = value.replace(/\s+/g, "");

  const submit = async () => {
    setBusy(true);
    setNotice({ kind: "info", text: "실행기에서 이 값으로 짧은 질문을 던져 확인하는 중입니다. 수 초에서 수십 초 걸립니다." });
    try {
      const fresh = await onSubmit(token);
      setValue("");
      setNotice({ kind: "ok", text: `넣었습니다. 기한 ${dateOf(fresh.expires_at)}` });
    } catch (e) {
      setNotice({ kind: "error", text: putErrorMessage(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={box}>
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-foreground">1년 토큰</span>
        <TokenBadge row={row} platformAsGroup={!platformView} />
        {row.expires_at && <span className="text-xs text-muted-foreground">기한 {dateOf(row.expires_at)}</span>}
      </div>
      <div className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
        실행기 {row.agent_name}, 계정 폴더 {folderName(row.config_dir)}.
        {row.connection_ids.length > 1 && ` 같은 폴더를 쓰는 연결 ${row.connection_ids.length}개가 이 토큰을 함께 씁니다.`}
      </div>
      {!row.reachable && (
        <div className="mt-1.5 text-xs text-warning">
          실행기가 꺼져 있어 {minuteOf(row.checked_at)} 에 마지막으로 확인한 값입니다.
        </div>
      )}
      {locked ? (
        <div className="mt-1.5 text-xs text-muted-foreground">Aimbase 가 빌려준 계정이라 토큰은 Aimbase 담당이 넣고 바꿉니다.</div>
      ) : (
        <>
          <div className="mt-2 text-xs leading-relaxed text-muted-foreground">
            그 Claude 계정으로 터미널에서 <code>claude setup-token</code> 을 치고, 찍힌 주소를 브라우저에서 그 계정으로 승인한 뒤
            마지막에 찍힌 <code>sk-ant-oat01-</code> 값을 붙여 넣으세요.
          </div>
          <div className="mt-2 flex gap-2">
            <Input
              type="password"
              autoComplete="off"
              spellCheck={false}
              disabled={busy}
              placeholder="sk-ant-oat01-..."
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
            <Button variant="primary" disabled={busy || token === ""} onClick={submit}>
              {row.present ? "바꾸기" : "토큰 넣기"}
            </Button>
          </div>
          {notice && (
            <div
              className={cn(
                "mt-1.5 text-xs",
                notice.kind === "ok" && "text-success",
                notice.kind === "error" && "text-destructive",
                notice.kind === "info" && "text-muted-foreground",
              )}
            >
              {notice.text}
            </div>
          )}
          {separateFromEdit && (
            <div className="mt-1.5 text-[11px] text-muted-foreground/70">
              토큰 넣기를 누르면 바로 실행기에 들어갑니다. 이 창의 「수정」 은 누르지 않아도 됩니다.
            </div>
          )}
        </>
      )}
    </div>
  );
}
