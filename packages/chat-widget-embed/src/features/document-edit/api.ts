import type { WidgetContext } from "../../frame/context";
import type { PreviewTarget } from "../preview";

/** GET /api/v1/chat/documents 의 판 한 칸(CR-181 T3-2). */
export interface DocVersion {
  version: number;
  base_version: number | null;
  made_by: "ORIGINAL" | "CHAT_EDIT" | "RESTORE";
  turn_id: string | null;
  size: number;
  created_at: string;
}

export interface DocInfo {
  document_id: string;
  kind: "BLOCK" | "FILE";
  name: string | null;
  turn_id: string | null;
  index: number | null;
  lang: string | null;
  latest_version: number;
  versions: DocVersion[];
}

/** 대화 끝 이벤트 document 칸과 되돌리기 응답. */
export interface DocResult {
  document_id: string;
  status: "UPDATED" | "UNCHANGED" | "FAILED";
  version?: number;
}

export function listDocuments(ctx: WidgetContext, sessionId: string): Promise<DocInfo[]> {
  return ctx.api.request<DocInfo[]>("GET", `/api/v1/chat/documents?session_id=${encodeURIComponent(sessionId)}`);
}

export function blockVersion(ctx: WidgetContext, documentId: string, version: number): Promise<{ lang: string; content: string }> {
  return ctx.api.request("GET", `/api/v1/chat/documents/${encodeURIComponent(documentId)}/versions/${version}`);
}

export function restoreVersion(ctx: WidgetContext, documentId: string, version: number): Promise<DocResult> {
  return ctx.api.request<DocResult>(
    "POST",
    `/api/v1/chat/documents/${encodeURIComponent(documentId)}/versions/${version}/restore`,
  );
}

/** CR-182 원본이 있는 파일인지. 미리보기 정보의 has_source 다. 변환 중이면 CR-180 이 부른 것과 같이 기다린다. */
export async function hasSource(ctx: WidgetContext, target: Extract<PreviewTarget, { kind: "file" }>): Promise<boolean> {
  const params = new URLSearchParams({ session_id: target.sessionId, name: target.name });
  if (target.version !== undefined) params.set("version", String(target.version));
  const info = await ctx.api.request<{ has_source?: boolean }>("GET", `/api/v1/chat/artifacts/preview?${params}`);
  return info.has_source === true;
}

export function readResult(extra: Readonly<Record<string, unknown>>): DocResult | null {
  const d = extra["document"] as Partial<DocResult> | undefined;
  if (!d || typeof d.document_id !== "string" || typeof d.status !== "string") return null;
  return { document_id: d.document_id, status: d.status, version: typeof d.version === "number" ? d.version : undefined };
}
