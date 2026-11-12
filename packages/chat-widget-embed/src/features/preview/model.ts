import type { ChatArtifact } from "../../types";

export type PreviewKindName = "PAGES" | "SHEET" | "HTML" | "SVG" | "IMAGE" | "MARKDOWN" | "TEXT" | "NONE";

export type PreviewTarget =
  | { kind: "file"; sessionId: string; name: string; modifiedAt?: string; version?: number }
  | { kind: "block"; turnId: string; index: number; lang: "html" | "svg"; source: string; version?: number };

/** 대화 끝 artifacts[] 항목. 위젯 본체 타입에 없는 칸은 서버가 CR-180 에서 더했다. */
export interface PreviewArtifact extends ChatArtifact {
  modified_at?: string;
  preview_kind?: PreviewKindName;
}

export interface SheetData {
  name: string;
  rows: string[][];
  row_count: number;
  col_count: number;
  truncated: boolean;
}

export interface PreviewInfo {
  name: string;
  size: number;
  modified_at: string;
  preview_kind: PreviewKindName;
  reason?: string;
  page_count?: number;
  page_width?: number;
  page_height?: number;
  sheets?: SheetData[];
}

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf(".");
  return dot < 0 ? "" : name.slice(dot + 1).toLowerCase();
}

export function sameTarget(a: PreviewTarget | null, b: PreviewTarget | null): boolean {
  if (!a || !b || a.kind !== b.kind) return false;
  if (a.kind === "file" && b.kind === "file") return a.sessionId === b.sessionId && a.name === b.name;
  if (a.kind === "block" && b.kind === "block") return a.turnId === b.turnId && a.index === b.index;
  return false;
}

export function targetName(target: PreviewTarget): string {
  return target.kind === "file" ? target.name : `${target.lang.toUpperCase()} #${target.index + 1}`;
}

function query(params: Record<string, string | number | undefined>): string {
  return Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join("&");
}

export function infoPath(t: Extract<PreviewTarget, { kind: "file" }>): string {
  return `/api/v1/chat/artifacts/preview?${query({ session_id: t.sessionId, name: t.name, version: t.version })}`;
}

/** 수정 시각을 주소에 실어 파일이 바뀌면 브라우저 캐시를 건너뛰게 한다(API 가이드 29-3). */
export function pagePath(t: Extract<PreviewTarget, { kind: "file" }>, page: number, modifiedAt?: string): string {
  return `/api/v1/chat/artifacts/preview/page?${query({
    session_id: t.sessionId, name: t.name, page, version: t.version, modified_at: modifiedAt,
  })}`;
}

/** 내려받기 API 는 파일 이름 인자가 file 이다(CR-132). version 은 CR-181 이 서버에 더한다. */
export function downloadPath(t: Extract<PreviewTarget, { kind: "file" }>): string {
  return `/api/v1/chat/artifacts/download?${query({ session_id: t.sessionId, file: t.name, version: t.version })}`;
}
