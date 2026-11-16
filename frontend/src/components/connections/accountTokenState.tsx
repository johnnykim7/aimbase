import { Badge, type BadgeColor } from "../common/Badge";
import type { AccountTokenRow } from "../../types/cliAccountToken";

// CR-196: 목록의 상태 칩과 1년 토큰 칸이 같은 기준으로 줄을 가른다. 순서가 곧 목록의 줄 순서다.
// unknown 은 토큰 상태를 불러오지 못한 줄이라 칩을 두지 않고 전체에서만 보인다.
export const TOKEN_STATES = ["expired", "missing", "soon", "ok", "platform", "unknown", "not_cli"] as const;
export type TokenState = (typeof TOKEN_STATES)[number];
export type ChipState = Exclude<TokenState, "unknown">;

export const CHIP_LABEL: Record<ChipState, string> = {
  expired: "토큰 기한 지남",
  missing: "토큰 없음",
  soon: "토큰 30일 이내",
  ok: "토큰 있음",
  platform: "Aimbase 관리",
  not_cli: "CLI 아님",
};

const SOON_DAYS = 30;

export const stateRank = (s: TokenState) => TOKEN_STATES.indexOf(s);

/** 토큰 값 자체의 상태. 관리 주체는 보지 않는다. */
export function presenceState(row: AccountTokenRow): "expired" | "missing" | "soon" | "ok" {
  if (!row.present) return "missing";
  const days = row.days_left ?? 0;
  if (days < 0) return "expired";
  if (days <= SOON_DAYS) return "soon";
  return "ok";
}

/** 연결 관리 화면의 줄 상태. Aimbase 관리 계정은 소비앱이 손댈 수 없어 한 무리로 둔다. */
export function tenantTokenState(row: AccountTokenRow | undefined, serverRunner: boolean): TokenState {
  if (!serverRunner) return "not_cli";
  if (!row) return "unknown";
  if (row.managed_by === "platform") return "platform";
  return presenceState(row);
}

export function folderName(configDir: string | undefined): string {
  if (!configDir) return "기본";
  return configDir.split("/").filter(Boolean).pop() ?? configDir;
}

// 서버가 Asia/Seoul 오프셋을 붙여 보내므로 글자 그대로 자르면 서울 날짜와 시각이다. 브라우저 시간대로 바꾸지 않는다.
export const dateOf = (s?: string) => (s ? s.slice(0, 10) : "-");
export const minuteOf = (s?: string) => (s ? s.slice(0, 16).replace("T", " ") : "-");

export function TokenBadge({ row, platformAsGroup }: { row: AccountTokenRow; platformAsGroup: boolean }) {
  if (platformAsGroup && row.managed_by === "platform") return <Badge color="muted">{CHIP_LABEL.platform}</Badge>;
  const state = presenceState(row);
  const color: Record<typeof state, BadgeColor> = { missing: "danger", expired: "danger", soon: "warning", ok: "success" };
  const text = state === "missing" ? "없음" : state === "expired" ? "기한 지남" : `${row.days_left}일 남음`;
  return <Badge color={color[state]}>{text}</Badge>;
}

/** 플랫폼 화면이 그 테넌트로 임퍼소네이션하지 못했을 때 화면 쪽에서 붙이는 오류 머리. 서버 오류 코드와 겹치지 않는다. */
export const IMPERSONATION_FAILED = "impersonation_failed:";

/** 토큰 넣기 실패를 사용자에게 보일 문장으로. 서버 오류 코드로 가른다. */
export function putErrorMessage(error: unknown): string {
  const code = error instanceof Error ? error.message : String(error);
  if (code === "account_token_invalid") return "setup-token 으로 받은 값이 아닙니다. sk-ant-oat01- 로 시작하는 값을 넣어 주세요.";
  if (code === "account_token_rejected")
    return "이 값으로 Claude 가 답하지 않았습니다. 그 계정에서 다시 받아 넣어 주세요. 넣어 둔 토큰은 그대로입니다.";
  if (code === "account_token_platform_managed") return "Aimbase 가 빌려준 계정이라 토큰은 Aimbase 담당이 넣고 바꿉니다.";
  if (code.startsWith("cli_runner_unreachable")) return "실행기가 꺼져 있어 넣지 못했습니다. 실행기를 켠 뒤 다시 해 주세요.";
  if (code.startsWith(IMPERSONATION_FAILED)) return `그 테넌트로 들어가지 못했습니다. ${code.slice(IMPERSONATION_FAILED.length)}`;
  return `넣지 못했습니다. ${code.split("\n")[0]}`;
}

export function recipientErrorValue(error: unknown): string | null {
  const code = error instanceof Error ? error.message : String(error);
  return code.startsWith("recipient_invalid:") ? code.slice("recipient_invalid:".length) : null;
}
