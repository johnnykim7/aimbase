import type { AnswerView, WidgetContext, WidgetFeature } from "../frame/context";
import { defineStrings } from "../frame/i18n";
import { svgIcon } from "../icons";
import {
  conversationState,
  type ExportFormat,
  exportFileName,
  exportFormats,
  saveBlob,
  viewerTimeZone,
} from "./chat-files";

/**
 * 응답 저장과 대화 내보내기(CR-182 4절, PRD-501, PRD-502). 서버가 그린 파일을 받아 브라우저 저장으로 넘긴다.
 * 형식 목록은 위젯 설정 export_formats 를 따른다. 서버에 PDF 글꼴이 없으면 pdf 가 빠져 온다.
 */

const S = defineStrings({
  ko: {
    save: "저장",
    exportChat: "대화 내보내기",
    pdf: "PDF",
    docx: "워드",
    md: "마크다운",
    exporting: "내보내는 중입니다",
    saveFailed: "저장하지 못했습니다",
    exportFailed: "내보내지 못했습니다",
    untitled: "대화",
  },
  en: {
    save: "Save",
    exportChat: "Export chat",
    pdf: "PDF",
    docx: "Word",
    md: "Markdown",
    exporting: "Exporting",
    saveFailed: "Couldn't save",
    exportFailed: "Couldn't export",
    untitled: "Chat",
  },
});

type Texts = (typeof S)["ko"];

/** 응답 하나는 PDF, 워드만 된다(T3-2 응답 저장). */
const ANSWER_FORMATS: readonly ExportFormat[] = ["pdf", "docx"];

const SAVE_ICON = '<path d="M12 15V3"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/>';
const EXPORT_ICON =
  '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M12 18v-6"/><path d="m9 15 3 3 3-3"/>';

function query(format: ExportFormat): string {
  const tz = viewerTimeZone();
  return `format=${format}${tz ? `&tz=${encodeURIComponent(tz)}` : ""}`;
}

class SaveExport {
  private readonly t: Texts;
  /** 열려 있는 저장 메뉴를 닫는 함수. 한 번에 하나만 연다 */
  private closeMenu: (() => void) | null = null;

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(S);
  }

  install(): void {
    this.installExport();
    this.installSave();
    this.ctx.ui.onEscape(() => {
      if (!this.closeMenu) return false;
      this.closeMenu();
      return true;
    });
    this.ctx.on("destroy", () => this.closeMenu?.());
  }

  private installExport(): void {
    const { ctx, t } = this;
    const state = conversationState(ctx);
    ctx.ui.moreMenu.addRow({
      id: "export-chat",
      order: 10,
      group: 0,
      visible: () => exportFormats(ctx).length > 0,
      render: () => {
        const row = document.createElement("div");
        row.className = "row sx-row";
        const label = document.createElement("span");
        label.className = "sx-label";
        const ic = document.createElement("span");
        ic.className = "mic";
        ic.innerHTML = svgIcon(EXPORT_ICON, 16);
        label.append(ic, document.createTextNode(t.exportChat));
        const cells = document.createElement("span");
        cells.className = "fsz";
        const empty = !state.hasContent();
        row.classList.toggle("sx-off", empty);
        for (const format of exportFormats(ctx)) {
          const b = document.createElement("button");
          b.type = "button";
          b.className = "chipb";
          b.textContent = t[format];
          b.disabled = empty;
          b.addEventListener("click", () => {
            ctx.ui.moreMenu.close();
            void this.exportChat(format);
          });
          cells.appendChild(b);
        }
        row.append(label, cells);
        return row;
      },
    });
  }

  private async exportChat(format: ExportFormat): Promise<void> {
    const { ctx, t } = this;
    const sessionId = ctx.sessionId();
    const title = conversationState(ctx).title(sessionId) ?? t.untitled;
    const at = new Date();
    ctx.ui.toast(t.exporting);
    try {
      const blob = await ctx.api.requestBlob(
        "GET",
        `/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/export?${query(format)}`,
      );
      saveBlob(blob, exportFileName(title, at, format));
    } catch {
      ctx.ui.toast(t.exportFailed);
    }
  }

  private installSave(): void {
    const { ctx } = this;
    ctx.ui.answer.addAction({
      id: "save-answer",
      order: 50,
      render: (a) => {
        if (!a.turnId || a.error || !a.text.trim()) return null;
        const formats = ANSWER_FORMATS.filter((f) => exportFormats(ctx).includes(f));
        if (formats.length === 0) return null;
        return this.saveButton(a, formats);
      },
    });
  }

  private saveButton(a: AnswerView, formats: ExportFormat[]): HTMLElement {
    const { t } = this;
    const wrap = document.createElement("span");
    wrap.className = "sx-wrap";
    const b = document.createElement("button");
    b.type = "button";
    b.className = "ib sm";
    b.title = t.save;
    b.setAttribute("aria-label", t.save);
    b.setAttribute("aria-haspopup", "menu");
    b.innerHTML = svgIcon(SAVE_ICON, 14);
    const menu = document.createElement("div");
    menu.className = "pop sx-pop";
    menu.setAttribute("role", "menu");
    for (const format of formats) {
      const mi = document.createElement("button");
      mi.type = "button";
      mi.className = "mi";
      mi.setAttribute("role", "menuitem");
      mi.textContent = t[format];
      mi.addEventListener("click", (e) => {
        e.stopPropagation();
        this.closeMenu?.();
        void this.saveAnswer(a, format, b);
      });
      menu.appendChild(mi);
    }
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      if (menu.classList.contains("on")) {
        this.closeMenu?.();
        return;
      }
      this.closeMenu?.();
      menu.classList.add("on");
      b.setAttribute("aria-expanded", "true");
      // 같은 누름이 바로 닫지 않게 다음 차례에 단다.
      const outside = (ev: Event) => {
        if (!ev.composedPath().includes(wrap)) this.closeMenu?.();
      };
      const root = wrap.getRootNode();
      setTimeout(() => root.addEventListener("click", outside, true), 0);
      this.closeMenu = () => {
        menu.classList.remove("on");
        b.setAttribute("aria-expanded", "false");
        root.removeEventListener("click", outside, true);
        this.closeMenu = null;
      };
    });
    wrap.append(b, menu);
    return wrap;
  }

  private async saveAnswer(a: AnswerView, format: ExportFormat, b: HTMLButtonElement): Promise<void> {
    const { ctx, t } = this;
    if (!a.turnId) return;
    const title = conversationState(ctx).title(a.sessionId) ?? t.untitled;
    b.disabled = true;
    b.classList.add("sx-busy");
    try {
      const blob = await ctx.api.requestBlob(
        "GET",
        `/api/v1/chat/sessions/${encodeURIComponent(a.sessionId)}/turns/${encodeURIComponent(a.turnId)}/export?${query(format)}`,
      );
      saveBlob(blob, exportFileName(title, a.at, format));
    } catch {
      ctx.ui.toast(t.saveFailed);
    } finally {
      b.disabled = false;
      b.classList.remove("sx-busy");
    }
  }
}

const CSS = `
.sx-row { flex-wrap: wrap; row-gap: 6px; }
.sx-row .sx-label { display: inline-flex; align-items: center; gap: 9px; white-space: nowrap; }
.sx-row .chipb { white-space: nowrap; }
.sx-row.sx-off { opacity: .45; }
.sx-row .chipb:disabled { cursor: default; }
.sx-wrap { position: relative; display: inline-flex; }
.sx-pop { bottom: calc(100% + 4px); right: 0; min-width: 120px; }
.ib.sx-busy svg { animation: sx-spin 1s linear infinite; }
@keyframes sx-spin { to { transform: rotate(360deg); } }
`;

export const saveExport: WidgetFeature = {
  id: "save-export",
  css: CSS,
  install(ctx) {
    new SaveExport(ctx).install();
  },
};
