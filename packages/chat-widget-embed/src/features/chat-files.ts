import type { WidgetContext } from "../frame/context";

/**
 * 파일 만들기, 저장, 내보내기, 공유 링크(CR-182)가 같이 쓰는 것.
 * 위젯 설정 칸과 파일 이름 규칙은 서버(CR-182 3.3, 3.6)와 맞춘다.
 */

/** 위젯 설정 조회 응답의 CR-182 칸(T3-2 위젯 설정 칸 추가). */
export interface FileShareSettings {
  file_create_enabled?: boolean;
  export_formats?: string[];
  share_enabled?: boolean;
  share_ttl_days?: number;
}

export type ExportFormat = "pdf" | "docx" | "md";

export const EXPORT_FORMATS: readonly ExportFormat[] = ["pdf", "docx", "md"];

export function fileShareSettings(ctx: WidgetContext): FileShareSettings | null {
  return ctx.widgetSettings() as unknown as FileShareSettings | null;
}

/** 서버가 내준 형식 가운데 위젯이 아는 것만, 서버 순서대로. PDF 글꼴이 없으면 서버가 pdf 를 빼고 준다. */
export function exportFormats(ctx: WidgetContext): ExportFormat[] {
  const given = fileShareSettings(ctx)?.export_formats ?? [];
  return given.filter((f): f is ExportFormat => (EXPORT_FORMATS as readonly string[]).includes(f));
}

/** 보는 사람 시간대. 서버가 파일 이름과 본문 시각을 이 시간대로 적는다. */
export function viewerTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "";
  } catch {
    return "";
  }
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

/** 화면 시각 yyyy-MM-dd HH:mm. */
export function shownTime(at: Date): string {
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

// 서버 CreateFileTool.cleanName 과 같은 규칙. 받은 Blob 에는 Content-Disposition 이 없어 이름을 여기서 짓는다.
const NAME_MAX = 120;
const UNSAFE = /[\\/:*?"<>|\u0000-\u001f\u007f]/g;

/** `<제목>_<yyyyMMdd-HHmm>.<형식>`. 시각은 보는 사람 시각이다. */
export function exportFileName(title: string, at: Date, ext: string): string {
  const base = title.replace(UNSAFE, "").replace(/^[.\s]+/, "").trim().slice(0, NAME_MAX).trim();
  const stamp = `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}`;
  return `${base ? `${base}_` : ""}${stamp}.${ext}`;
}

export function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.rel = "noopener";
  link.click();
  // 내려받기가 시작된 뒤에 거둔다. 바로 거두면 일부 브라우저가 받지 못한다.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

/**
 * 지금 대화의 제목과, 질문이 하나라도 오간 대화인지. 지난 대화를 열면 제목이 오므로 그때도 비지 않은 대화로 본다.
 * 비었으면 내보내기와 공유를 흐리게 둔다(T3-4).
 */
export class ConversationState {
  private readonly titles = new Map<string, string>();
  private readonly used = new Set<string>();

  constructor(private readonly ctx: WidgetContext) {
    ctx.on("answerStart", (a) => this.used.add(a.sessionId));
    ctx.on("answerDone", (a) => this.used.add(a.sessionId));
    ctx.on("titleChange", ({ sessionId, title }) => {
      this.used.add(sessionId);
      if (title) this.titles.set(sessionId, title);
    });
    ctx.on("destroy", () => {
      this.titles.clear();
      this.used.clear();
    });
  }

  hasContent(): boolean {
    return this.used.has(this.ctx.sessionId());
  }

  title(sessionId = this.ctx.sessionId()): string | undefined {
    return this.titles.get(sessionId);
  }
}

const states = new WeakMap<WidgetContext, ConversationState>();

/** 저장, 내보내기, 공유가 한 위젯에서 같은 기록을 보게 한 벌만 둔다. */
export function conversationState(ctx: WidgetContext): ConversationState {
  let s = states.get(ctx);
  if (!s) {
    s = new ConversationState(ctx);
    states.set(ctx, s);
  }
  return s;
}
