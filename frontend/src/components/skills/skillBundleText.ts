import type { SkillBundleSummary, SkillBundleWarning } from "../../types/skillBundle";
import type { BadgeColor } from "../common/Badge";

// 서버는 경고를 코드로만 준다(T3-17 3.2 의 7). 문구는 화면이 정한다.
const WARNING_TEXT: Record<string, string> = {
  MISSING_REFERENCE: "안내서가 가리키는 파일이 묶음에 없습니다",
  FOLDER_NAME_MISMATCH: "묶음 폴더 이름과 스킬 이름이 다릅니다",
  UNSUPPORTED_SCRIPT: "실행 컨테이너가 돌리지 못하는 스크립트입니다",
};

export const warningText = (w: SkillBundleWarning) => WARNING_TEXT[w.code] ?? w.code;

export const SCOPE_LABEL = { tenant: "우리 전용", platform: "공용" } as const;

export function bundleStatus(b: Pick<SkillBundleSummary, "is_active" | "shadowed">): {
  label: string;
  color: BadgeColor;
} {
  if (!b.is_active) return { label: "비활성", color: "muted" };
  if (b.shadowed) return { label: "우리 전용에 가려짐", color: "warning" };
  return { label: "활성", color: "success" };
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export const formatDateTime = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" }) : "--";
