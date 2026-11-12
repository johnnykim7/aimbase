import { copyText } from "../clipboard";
import type { WidgetContext, WidgetFeature } from "../frame/context";
import { defineStrings } from "../frame/i18n";
import { svgIcon } from "../icons";
import { conversationState, fileShareSettings, shownTime } from "./chat-files";

/**
 * 공유 링크(CR-182 4절, PRD-503). 더 보기 「공유 링크 만들기」 와 파일 칩의 「공유」 가 공유 창을 연다.
 * 링크는 만든 순간의 사본을 가리키고, 받는 사람은 로그인 없이 연다(T3-2 공유 링크).
 */

const S = defineStrings({
  ko: {
    shareChat: "공유 링크 만들기",
    share: "공유",
    title: "공유 링크",
    noticeAnyone: "링크를 가진 사람은 누구나 볼 수 있습니다.",
    noticeSnapshot: "링크를 만든 시점의 내용만 보입니다.",
    ttl: (n: number) => `기한 ${n}일`,
    create: "링크 만들기",
    copy: "복사",
    copied: "복사했습니다",
    copyFailed: "복사하지 못했습니다",
    until: (at: string) => `기한 ${at} 까지`,
    active: "사용 중",
    revoked: "끊김",
    expired: "기한 지남",
    views: (n: number) => `열린 수 ${n}`,
    revoke: "링크 끊기",
    createFailed: "공유 링크를 만들지 못했습니다",
    revokeFailed: "링크를 끊지 못했습니다",
    listFailed: "만든 링크를 불러오지 못했습니다",
    close: "닫기",
    untitled: "대화",
  },
  en: {
    shareChat: "Create share link",
    share: "Share",
    title: "Share link",
    noticeAnyone: "Anyone with the link can view it.",
    noticeSnapshot: "Only the content at the time the link was created is shown.",
    ttl: (n: number) => `Expires in ${n} days`,
    create: "Create link",
    copy: "Copy",
    copied: "Copied",
    copyFailed: "Couldn't copy",
    until: (at: string) => `Expires ${at}`,
    active: "Active",
    revoked: "Revoked",
    expired: "Expired",
    views: (n: number) => `Opened ${n}`,
    revoke: "Revoke link",
    createFailed: "Couldn't create the share link",
    revokeFailed: "Couldn't revoke the link",
    listFailed: "Couldn't load the links",
    close: "Close",
    untitled: "Chat",
  },
});

type Texts = (typeof S)["ko"];

type ShareKind = "CONVERSATION" | "FILE";
type ShareStatus = "ACTIVE" | "REVOKED" | "EXPIRED";

/** 공유 링크 만들기, 목록, 끊기 응답의 한 행(T3-2). */
interface ShareRow {
  share_id: string;
  url: string;
  kind: ShareKind;
  file_name: string | null;
  status: ShareStatus;
  view_count: number;
  expires_at: string;
  created_at: string;
  revoked_at: string | null;
}

interface Target {
  kind: ShareKind;
  sessionId: string;
  fileName?: string;
  /** 창에 보이는 대상 이름. 대화는 제목, 파일은 파일 이름 */
  name: string;
}

type Say = (text: string, error?: boolean) => void;

const SHARE_ICON =
  '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>';
const CLOSE_ICON = '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>';

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function textButton(cls: string, label: string, onClick: () => void): HTMLButtonElement {
  const b = el("button", cls, label);
  b.type = "button";
  b.addEventListener("click", (e) => {
    e.stopPropagation();
    onClick();
  });
  return b;
}

function shown(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : shownTime(d);
}

class ShareLink {
  private readonly t: Texts;
  /** 열려 있는 공유 창을 닫는 함수. 한 번에 하나만 연다 */
  private close: (() => void) | null = null;

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(S);
  }

  private enabled(): boolean {
    return fileShareSettings(this.ctx)?.share_enabled === true;
  }

  install(): void {
    const { ctx, t } = this;
    const state = conversationState(ctx);
    ctx.ui.moreMenu.addRow({
      id: "share-link",
      order: 20,
      group: 0,
      visible: () => this.enabled(),
      render: () => {
        const b = el("button", "mi");
        b.type = "button";
        b.setAttribute("role", "menuitem");
        const ic = el("span", "mic");
        ic.innerHTML = svgIcon(SHARE_ICON, 16);
        b.append(ic, el("span", "ml", t.shareChat));
        b.disabled = !state.hasContent();
        b.addEventListener("click", () => {
          ctx.ui.moreMenu.close();
          const sessionId = ctx.sessionId();
          this.open({ kind: "CONVERSATION", sessionId, name: state.title(sessionId) ?? t.untitled });
        });
        return b;
      },
    });
    ctx.ui.answer.addArtifactAction({
      id: "share-link",
      order: 10,
      render: (f, a) => {
        if (!this.enabled()) return null;
        return textButton("shr-chip", t.share, () =>
          this.open({ kind: "FILE", sessionId: a.sessionId, fileName: f.name, name: f.name }),
        );
      },
    });
    ctx.ui.onEscape(() => {
      if (!this.close) return false;
      this.close();
      return true;
    });
    ctx.on("newConversation", () => this.close?.());
    ctx.on("destroy", () => this.close?.());
  }

  private open(target: Target): void {
    this.close?.();
    const { t } = this;
    const back = el("div", "shr-back");
    const box = el("div", "shr-box");
    box.setAttribute("role", "dialog");
    box.setAttribute("aria-modal", "true");
    box.setAttribute("aria-label", t.title);

    const head = el("div", "shr-head");
    const closeBtn = el("button", "ib sm");
    closeBtn.type = "button";
    closeBtn.title = t.close;
    closeBtn.setAttribute("aria-label", t.close);
    closeBtn.innerHTML = svgIcon(CLOSE_ICON, 14);
    head.append(el("strong", "shr-title", t.title), closeBtn);

    const notes = el("div", "shr-note");
    notes.append(el("div", undefined, t.noticeAnyone), el("div", undefined, t.noticeSnapshot));
    const createBtn = el("button", "shr-primary", t.create);
    createBtn.type = "button";
    const result = el("div", "shr-result");
    result.hidden = true;
    const msg = el("div", "shr-msg");
    const listBox = el("div", "shr-list");

    box.append(head, el("div", "shr-target", target.name), notes);
    const days = fileShareSettings(this.ctx)?.share_ttl_days;
    if (days) box.appendChild(el("div", "shr-ttl", t.ttl(days)));
    box.append(createBtn, result, msg, listBox);
    back.appendChild(box);

    const say: Say = (text, error = false) => {
      msg.textContent = text;
      msg.classList.toggle("err", error);
    };
    const rows: ShareRow[] = [];
    const renderList = () => this.renderList(listBox, rows, say);

    createBtn.addEventListener("click", async () => {
      createBtn.disabled = true;
      say("");
      try {
        const body: Record<string, unknown> = { session_id: target.sessionId, kind: target.kind };
        if (target.kind === "FILE") body.file_name = target.fileName;
        const row = await this.ctx.api.request<ShareRow>("POST", "/api/v1/chat/shares", body);
        this.renderResult(result, row, say);
        rows.unshift(row);
        renderList();
      } catch {
        say(t.createFailed, true);
      } finally {
        createBtn.disabled = false;
      }
    });

    const close = () => {
      back.remove();
      this.close = null;
    };
    closeBtn.addEventListener("click", close);
    back.addEventListener("click", (e) => {
      if (e.target === back) close();
    });
    this.close = close;
    this.ctx.ui.layer.appendChild(back);

    // 목록은 이 대화에서 만든 링크 전부다. 파일 공유 창에서도 같은 대화의 대화 링크가 같이 보인다.
    void this.ctx.api
      .request<{ shares: ShareRow[] }>("GET", `/api/v1/chat/shares?session_id=${encodeURIComponent(target.sessionId)}`)
      .then((data) => {
        for (const r of data?.shares ?? []) {
          if (!rows.some((x) => x.share_id === r.share_id)) rows.push(r);
        }
        renderList();
      })
      .catch(() => say(t.listFailed, true));
  }

  private renderResult(result: HTMLElement, row: ShareRow, say: Say): void {
    const { t } = this;
    const line = el("div", "shr-linkrow");
    line.append(el("div", "shr-url", row.url), textButton("chipb", t.copy, () => void this.copy(row.url, say)));
    result.replaceChildren(line, el("div", "shr-until", t.until(shown(row.expires_at))));
    result.hidden = false;
  }

  private renderList(box: HTMLElement, rows: ShareRow[], say: Say): void {
    const { t } = this;
    box.replaceChildren();
    for (const row of rows) {
      const r = el("div", "shr-row");
      const name = row.kind === "FILE" ? (row.file_name ?? "") : t.untitled;
      const status = row.status === "ACTIVE" ? t.active : row.status === "REVOKED" ? t.revoked : t.expired;
      const info = el("div", "shr-info");
      info.append(
        el("span", "shr-name", name),
        el("span", "shr-meta", `${shown(row.created_at)} · ${status} · ${t.views(row.view_count)}`),
      );
      r.appendChild(info);
      if (row.status === "ACTIVE") {
        const acts = el("span", "shr-acts");
        acts.append(
          textButton("chipb", t.copy, () => void this.copy(row.url, say)),
          textButton("chipb", t.revoke, () => void this.revoke(row, box, rows, say)),
        );
        r.appendChild(acts);
      } else {
        r.classList.add("off");
      }
      box.appendChild(r);
    }
  }

  private async revoke(row: ShareRow, box: HTMLElement, rows: ShareRow[], say: Say): Promise<void> {
    say("");
    try {
      const updated = await this.ctx.api.request<ShareRow>(
        "DELETE",
        `/api/v1/chat/shares/${encodeURIComponent(row.share_id)}`,
      );
      Object.assign(row, updated ?? { status: "REVOKED" });
      this.renderList(box, rows, say);
    } catch {
      say(this.t.revokeFailed, true);
    }
  }

  private async copy(text: string, say: Say): Promise<void> {
    const ok = await copyText(text);
    say(ok ? this.t.copied : this.t.copyFailed, !ok);
  }
}

const CSS = `
.pop .mi:disabled { opacity: .45; cursor: default; background: transparent; }
.shr-chip { margin-left: 4px; padding: 1px 7px; border: 1px solid var(--aimbase-border); border-radius: 999px; background: var(--aimbase-bg); color: var(--aimbase-muted); font: inherit; font-size: .78em; cursor: pointer; }
.shr-chip:hover { color: var(--aimbase-fg); background: var(--aimbase-chip-hover); }
.shr-back { position: absolute; inset: 0; z-index: 45; display: flex; align-items: center; justify-content: center; padding: 16px; background: rgba(0, 0, 0, .35); }
.shr-box { width: min(100%, 380px); max-height: 100%; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; padding: 14px 16px; border-radius: var(--aimbase-radius); background: var(--aimbase-bg); color: var(--aimbase-fg); box-shadow: 0 10px 30px rgba(0, 0, 0, .18); font-size: .92em; }
.shr-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.shr-title { font-size: 1.05em; }
.shr-target { font-weight: 600; overflow-wrap: anywhere; }
.shr-note, .shr-ttl, .shr-until, .shr-meta { color: var(--aimbase-muted); font-size: .92em; }
.shr-primary { align-self: flex-start; padding: 6px 14px; border: 0; border-radius: 8px; background: var(--aimbase-primary); color: var(--aimbase-on-primary); font: inherit; cursor: pointer; }
.shr-primary:hover { background: var(--aimbase-primary-hover); }
.shr-primary:disabled { opacity: .6; cursor: default; }
.shr-result { display: flex; flex-direction: column; gap: 4px; }
.shr-result[hidden] { display: none; }
.shr-linkrow { display: flex; align-items: center; gap: 6px; }
.shr-url { flex: 1; min-width: 0; padding: 5px 8px; border: 1px solid var(--aimbase-border); border-radius: 6px; background: var(--aimbase-chip-bg); font-size: .9em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; user-select: all; }
.shr-msg { font-size: .9em; color: var(--aimbase-muted); }
.shr-msg:empty { display: none; }
.shr-msg.err { color: var(--aimbase-danger); }
.shr-list { display: flex; flex-direction: column; gap: 6px; border-top: 1px solid var(--aimbase-border); padding-top: 8px; }
.shr-list:empty { display: none; }
.shr-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.shr-row.off { opacity: .6; }
.shr-info { display: flex; flex-direction: column; min-width: 0; }
.shr-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shr-acts { display: inline-flex; gap: 4px; flex-shrink: 0; }
`;

export const shareLink: WidgetFeature = {
  id: "share-link",
  css: CSS,
  install(ctx) {
    new ShareLink(ctx).install();
  },
};
