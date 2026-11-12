import type { WidgetContext, WidgetFeature } from "../frame/context";
import { defineStrings, type Locale } from "../frame/i18n";
import { icon, svgIcon } from "../icons";
import { WidgetApiError } from "../widget-api";

/**
 * 위젯 사용자 선호 기억(CR-179, PRD-440 ~ PRD-442). 더 보기 「기억과 선호」 창에서 내 선호를 쓰고
 * 자동 기억을 켜고 끄고 지운다. 사람이 바꾼 모델과 깊게 생각하기는 저장해 두었다가 뜰 때와 새 대화에 다시 고른다.
 * 대화에 넣는 일은 서버가 한다(BIZ-181). 화면 구조는 T3-3, 동작은 T3-4 같은 이름 절이다.
 */

const S = defineStrings({
  ko: {
    title: "기억과 선호",
    close: "닫기",
    prefsTitle: "내 선호",
    prefsHint: "AI 가 대화마다 참고합니다. 예: 호칭, 답 길이와 말투, 하는 일",
    save: "저장",
    saved: "저장했습니다. 다음 질문부터 적용됩니다",
    tooLong: (n: number) => `${n}자까지 쓸 수 있습니다`,
    saveFailed: "저장하지 못했습니다",
    memoryTitle: "자동 기억",
    memoryHint: "대화에서 알게 된 선호를 기억해 다음 대화에 씁니다",
    memoryOff: "꺼져 있는 동안에는 기억을 대화에 쓰지 않고 더 만들지도 않습니다",
    toggleFailed: "바꾸지 못했습니다",
    empty: "아직 기억한 것이 없습니다",
    deleteOne: "삭제",
    deleteFailed: "삭제하지 못했습니다",
    deleteAll: "모두 삭제",
    confirmDeleteAll: (n: number) => `기억 ${n}개를 모두 삭제할까요?`,
    confirmDelete: "삭제",
    cancel: "취소",
    loading: "불러오는 중",
    loadFailed: "불러오지 못했습니다",
    retry: "다시 시도",
    unsaved: "저장하지 않은 내용이 있습니다. 닫을까요?",
    discard: "닫기",
  },
  en: {
    title: "Memory and preferences",
    close: "Close",
    prefsTitle: "My preferences",
    prefsHint: "The AI refers to these in every conversation. For example: how to address you, answer length and tone, your work",
    save: "Save",
    saved: "Saved. This applies from your next question",
    tooLong: (n: number) => `You can write up to ${n} characters`,
    saveFailed: "Couldn't save",
    memoryTitle: "Auto memory",
    memoryHint: "Remembers preferences learned in conversations and uses them in later ones",
    memoryOff: "While this is off, memories are neither used in conversations nor created",
    toggleFailed: "Couldn't change the setting",
    empty: "Nothing remembered yet",
    deleteOne: "Delete",
    deleteFailed: "Couldn't delete",
    deleteAll: "Delete all",
    confirmDeleteAll: (n: number) => (n === 1 ? "Delete 1 memory?" : `Delete all ${n} memories?`),
    confirmDelete: "Delete",
    cancel: "Cancel",
    loading: "Loading",
    loadFailed: "Couldn't load",
    retry: "Try again",
    unsaved: "You have unsaved changes. Close anyway?",
    discard: "Close",
  },
});

type Texts = (typeof S)["ko"];

const PREFERENCES_PATH = "/api/v1/chat/me/preferences";
const MEMORIES_PATH = "/api/v1/chat/me/memories";

const MEMORY_ICON = '<path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z"/>';
const TRASH_ICON =
  '<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>';

const DATE_LOCALES: Record<Locale, string> = { ko: "ko-KR", en: "en-US" };

/** GET, PUT /chat/me/preferences 응답(T3-2). */
interface Preferences {
  instructions: string;
  instructions_max_chars: number;
  memory_enabled: boolean;
  last_model: string | null;
  last_thinking: boolean | null;
}

interface MemoryItem {
  id: string;
  content: string;
  created_at: string;
}

type ConfirmKind = "deleteAll" | "unsaved";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function button(className: string, text: string, onClick: () => void): HTMLButtonElement {
  const b = el("button", className, text);
  b.type = "button";
  b.addEventListener("click", (e) => {
    e.stopPropagation();
    onClick();
  });
  return b;
}

function spinner(): HTMLSpanElement {
  const s = el("span", "amem-spin");
  s.setAttribute("aria-hidden", "true");
  return s;
}

class Memory {
  private readonly t: Texts;
  /** 뜰 때 내 선호 조회가 성공했는지. 테넌트가 껐으면(409) 메뉴를 숨긴다 */
  private available = false;
  private settingsSeen = false;
  /** 토큰은 설정을 받을 때 처음 오므로 user_ref 여부는 첫 settingsLoaded 에서 한 번만 본다 */
  private availabilityAsked = false;
  private lastModel: string | null = null;
  private lastThinking: boolean | null = null;

  private root: HTMLDivElement | null = null;
  private body: HTMLDivElement | null = null;
  private prefsSec: HTMLElement | null = null;
  private memSec: HTMLElement | null = null;
  private confirmLayer: HTMLDivElement | null = null;
  private offEscape: (() => void) | null = null;
  /** 창을 닫았다 다시 열었을 때 앞서 보낸 조회의 늦은 응답을 버리려고 센다 */
  private generation = 0;

  private loading = false;
  private failed = false;
  private prefs: Preferences | null = null;
  /** 최근 것부터 */
  private memories: MemoryItem[] = [];
  private draft = "";
  private saving = false;
  private tooLong = false;
  private toggling = false;
  private deletingAll = false;
  private confirm: ConfirmKind | null = null;

  private textarea: HTMLTextAreaElement | null = null;
  private countEl: HTMLSpanElement | null = null;
  private errEl: HTMLSpanElement | null = null;
  private saveBtn: HTMLButtonElement | null = null;

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(S);
  }

  install(): void {
    const { ctx, t } = this;
    ctx.ui.moreMenu.addItem({
      id: "memory",
      order: 40,
      group: 0,
      icon: svgIcon(MEMORY_ICON, 16),
      label: () => t.title,
      visible: () => this.available,
      onSelect: () => this.open(),
    });
    // 위젯 본체는 설정을 다시 받을 때(화면 언어를 바꿀 때 포함) 깊게 생각하기를 처음 값으로 돌리므로 받을 때마다 다시 고른다
    ctx.on("settingsLoaded", () => {
      this.settingsSeen = true;
      if (!this.availabilityAsked) {
        this.availabilityAsked = true;
        // 주인이 없는 토큰은 서버가 403 으로 거절하므로 묻지 않고 메뉴를 숨긴 채 둔다(BIZ-180)
        if (ctx.api.hasUserRef()) void this.loadAvailability();
      }
      this.applyLast();
    });
    ctx.on("newConversation", () => this.applyLast());
    ctx.on("modelChange", ({ model, thinking }) => this.rememberModel(model, thinking));
    ctx.on("localeChange", () => this.render());
    ctx.on("destroy", () => this.close());
  }

  private async loadAvailability(): Promise<void> {
    try {
      const p = await this.ctx.api.request<Preferences>("GET", PREFERENCES_PATH);
      this.available = true;
      this.lastModel = p.last_model;
      this.lastThinking = p.last_thinking;
      this.applyLast();
    } catch {
      this.available = false;
    }
  }

  /** 목록 밖 모델이나 지원하지 않는 깊게 생각하기는 틀이 거짓을 돌려주고 위젯 본체 값이 그대로 남는다(BIZ-185) */
  private applyLast(): void {
    if (!this.available || !this.settingsSeen) return;
    if (this.lastModel) this.ctx.chat.selectModel(this.lastModel);
    if (this.lastThinking !== null) this.ctx.chat.setThinking(this.lastThinking);
  }

  private rememberModel(model: string | undefined, thinking: boolean): void {
    if (!this.available) return;
    this.lastModel = model ?? null;
    this.lastThinking = thinking;
    // 저장에 실패해도 대화에는 영향이 없으므로 알리지 않는다(T3-4)
    this.ctx.api
      .request("PUT", PREFERENCES_PATH, { last_model: this.lastModel, last_thinking: thinking })
      .catch(() => undefined);
  }

  private open(): void {
    if (this.root) return;
    const root = el("div", "amem-ov");
    root.setAttribute("role", "dialog");
    root.setAttribute("aria-modal", "true");
    root.addEventListener("click", (e) => e.stopPropagation());
    this.root = root;
    this.offEscape = this.ctx.ui.onEscape(() => {
      if (this.confirm) this.setConfirm(null);
      else this.requestClose();
      return true;
    });
    this.ctx.ui.layer.appendChild(root);
    void this.reload();
  }

  private close(): void {
    this.generation++;
    this.offEscape?.();
    this.offEscape = null;
    this.root?.remove();
    this.root = this.body = this.prefsSec = this.memSec = this.confirmLayer = null;
    this.textarea = this.countEl = this.errEl = this.saveBtn = null;
    this.confirm = null;
    this.tooLong = false;
  }

  private requestClose(): void {
    if (this.dirty()) this.setConfirm("unsaved");
    else this.close();
  }

  private dirty(): boolean {
    return this.prefs !== null && this.draft !== this.prefs.instructions;
  }

  private async reload(): Promise<void> {
    const gen = ++this.generation;
    this.loading = true;
    this.failed = false;
    this.render();
    try {
      const [prefs, memories] = await Promise.all([
        this.ctx.api.request<Preferences>("GET", PREFERENCES_PATH),
        this.ctx.api.request<MemoryItem[]>("GET", MEMORIES_PATH),
      ]);
      if (gen !== this.generation) return;
      this.prefs = prefs;
      this.draft = prefs.instructions ?? "";
      this.memories = [...memories].reverse();
      this.loading = false;
      this.render();
      this.textarea?.focus();
    } catch {
      if (gen !== this.generation) return;
      this.loading = false;
      this.failed = true;
      this.render();
    }
  }

  private render(): void {
    const root = this.root;
    if (!root) return;
    const t = this.t;
    root.setAttribute("aria-label", t.title);

    const ta = this.textarea;
    const active = (root.getRootNode() as Document | ShadowRoot).activeElement;
    const selection = ta !== null && active === ta ? [ta.selectionStart, ta.selectionEnd] : null;
    const scrollTop = this.body?.scrollTop ?? 0;

    const head = el("div", "amem-head");
    head.append(el("h2", "amem-title", t.title));
    const x = button("amem-x", "", () => this.requestClose());
    x.innerHTML = icon("x");
    x.setAttribute("aria-label", t.close);
    x.title = t.close;
    head.append(x);

    const body = el("div", "amem-body");
    this.body = body;
    this.textarea = this.countEl = this.errEl = this.saveBtn = null;
    if (this.failed) {
      const fail = el("div", "amem-fail");
      fail.append(el("p", undefined, t.loadFailed), button("amem-btn", t.retry, () => void this.reload()));
      body.append(fail);
      this.prefsSec = this.memSec = null;
    } else {
      this.prefsSec = el("section", "amem-sec");
      this.memSec = el("section", "amem-sec");
      body.append(this.prefsSec, this.memSec);
      this.renderPrefs();
      this.renderMemories();
    }

    this.confirmLayer = el("div", "amem-cov");
    root.replaceChildren(head, body, this.confirmLayer);
    this.renderConfirm();
    body.scrollTop = scrollTop;
    const next = this.textarea as HTMLTextAreaElement | null;
    if (selection && next) {
      next.focus();
      next.setSelectionRange(selection[0], selection[1]);
    }
  }

  private loadingLine(): HTMLDivElement {
    const line = el("div", "amem-loading");
    line.append(spinner(), el("span", undefined, this.t.loading));
    return line;
  }

  private renderPrefs(): void {
    const sec = this.prefsSec;
    if (!sec) return;
    const t = this.t;
    sec.replaceChildren(el("h3", undefined, t.prefsTitle));
    if (this.loading || !this.prefs) {
      sec.append(this.loadingLine());
      return;
    }
    const ta = el("textarea", "amem-ta");
    ta.rows = 5;
    ta.value = this.draft;
    ta.setAttribute("aria-label", t.prefsTitle);
    // 글자마다 다시 그리면 한글 조합이 끊기므로 아래 줄만 고친다
    ta.addEventListener("input", () => {
      this.draft = ta.value;
      this.tooLong = false;
      this.updateFoot();
    });
    const foot = el("div", "amem-foot");
    this.errEl = el("span", "amem-err");
    this.countEl = el("span", "amem-count");
    this.saveBtn = button("amem-btn primary", t.save, () => void this.save());
    foot.append(this.errEl, this.countEl, this.saveBtn);
    this.textarea = ta;
    sec.append(el("p", "amem-hint", t.prefsHint), ta, foot);
    this.updateFoot();
  }

  private maxChars(): number {
    return this.prefs?.instructions_max_chars ?? 0;
  }

  private canSave(): boolean {
    return !this.saving && this.dirty() && this.draft.length <= this.maxChars();
  }

  private updateFoot(): void {
    const t = this.t;
    const max = this.maxChars();
    if (this.countEl) {
      this.countEl.textContent = `${this.draft.length} / ${max}`;
      this.countEl.classList.toggle("over", this.draft.length > max);
    }
    if (this.errEl) this.errEl.textContent = this.tooLong ? t.tooLong(max) : "";
    if (this.saveBtn) {
      this.saveBtn.disabled = !this.canSave();
      if (this.saving) this.saveBtn.replaceChildren(spinner(), document.createTextNode(t.save));
      else this.saveBtn.textContent = t.save;
    }
  }

  private async save(): Promise<void> {
    if (!this.prefs || !this.canSave()) return;
    const sent = this.draft;
    this.saving = true;
    this.updateFoot();
    try {
      const p = await this.ctx.api.request<Preferences>("PUT", PREFERENCES_PATH, { instructions: sent });
      this.prefs.instructions = p.instructions ?? "";
      this.prefs.instructions_max_chars = p.instructions_max_chars;
      // 서버는 앞뒤 빈칸을 덜어 저장하므로, 저장하는 동안 더 쓰지 않았으면 칸도 저장된 글로 맞춘다
      if (this.draft === sent) {
        this.draft = this.prefs.instructions;
        if (this.textarea && this.textarea.value !== this.draft) this.textarea.value = this.draft;
      }
      this.ctx.ui.toast(this.t.saved);
    } catch (e) {
      if (e instanceof WidgetApiError && e.status === 400 && e.code === "preferences_too_long") this.tooLong = true;
      else this.ctx.ui.toast(this.t.saveFailed);
    } finally {
      this.saving = false;
      this.updateFoot();
    }
  }

  private renderMemories(): void {
    const sec = this.memSec;
    if (!sec) return;
    const t = this.t;
    const head = el("div", "amem-sechead");
    head.append(el("h3", undefined, t.memoryTitle));
    sec.replaceChildren(head);
    const prefs = this.prefs;
    if (this.loading || !prefs) {
      sec.append(this.loadingLine());
      return;
    }

    const sw = button("amem-switch", "", () => void this.toggleMemory());
    sw.setAttribute("role", "switch");
    sw.setAttribute("aria-checked", String(prefs.memory_enabled));
    sw.setAttribute("aria-label", t.memoryTitle);
    sw.disabled = this.toggling;
    sw.append(el("span"));
    head.append(sw);
    sec.append(el("p", "amem-hint", t.memoryHint));
    if (!prefs.memory_enabled) sec.append(el("div", "amem-off", t.memoryOff));

    if (this.memories.length === 0) {
      sec.append(el("p", "amem-empty", t.empty));
      return;
    }
    const list = el("ul", "amem-list");
    for (const m of this.memories) {
      const item = el("li", "amem-item");
      const text = el("div", "amem-mtext", m.content);
      text.append(el("span", "amem-mdate", this.formatDate(m.created_at)));
      const del = button("amem-del", "", () => void this.deleteOne(m.id));
      del.innerHTML = svgIcon(TRASH_ICON, 14);
      del.setAttribute("aria-label", t.deleteOne);
      del.title = t.deleteOne;
      del.disabled = this.deletingAll;
      item.append(text, del);
      list.append(item);
    }
    const tail = el("div", "amem-tail");
    const all = button("amem-btn danger", t.deleteAll, () => this.setConfirm("deleteAll"));
    all.disabled = this.deletingAll;
    tail.append(all);
    sec.append(list, tail);
  }

  private formatDate(iso: string): string {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return "";
    return d.toLocaleDateString(DATE_LOCALES[this.ctx.locale()], { year: "numeric", month: "short", day: "numeric" });
  }

  private async toggleMemory(): Promise<void> {
    const prefs = this.prefs;
    if (!prefs || this.toggling) return;
    const next = !prefs.memory_enabled;
    prefs.memory_enabled = next;
    this.toggling = true;
    this.renderMemories();
    try {
      const p = await this.ctx.api.request<Preferences>("PUT", PREFERENCES_PATH, { memory_enabled: next });
      prefs.memory_enabled = p.memory_enabled;
    } catch {
      prefs.memory_enabled = !next;
      this.ctx.ui.toast(this.t.toggleFailed);
    } finally {
      this.toggling = false;
      this.renderMemories();
    }
  }

  private async deleteOne(id: string): Promise<void> {
    const index = this.memories.findIndex((m) => m.id === id);
    if (index < 0) return;
    const [removed] = this.memories.splice(index, 1);
    this.renderMemories();
    try {
      await this.ctx.api.request("DELETE", `${MEMORIES_PATH}/${encodeURIComponent(id)}`);
    } catch (e) {
      // 404 는 다른 화면에서 이미 지운 것이므로 지운 대로 둔다(T3-4 단계 9)
      if (e instanceof WidgetApiError && e.status === 404) return;
      this.memories.splice(Math.min(index, this.memories.length), 0, removed);
      this.renderMemories();
      this.ctx.ui.toast(this.t.deleteFailed);
    }
  }

  private async deleteAll(): Promise<void> {
    this.setConfirm(null);
    this.deletingAll = true;
    this.renderMemories();
    try {
      await this.ctx.api.request("DELETE", MEMORIES_PATH);
      this.memories = [];
    } catch {
      this.ctx.ui.toast(this.t.deleteFailed);
    } finally {
      this.deletingAll = false;
      this.renderMemories();
    }
  }

  private setConfirm(kind: ConfirmKind | null): void {
    this.confirm = kind;
    this.renderConfirm();
    if (!kind) this.textarea?.focus();
  }

  private renderConfirm(): void {
    const layer = this.confirmLayer;
    if (!layer) return;
    const t = this.t;
    const kind = this.confirm;
    layer.hidden = kind === null;
    layer.replaceChildren();
    if (!kind) return;
    const cancel = () => this.setConfirm(null);
    const box = el("div", "amem-confirm");
    box.setAttribute("role", "alertdialog");
    box.addEventListener("click", (e) => e.stopPropagation());
    const btns = el("div", "amem-confirm-btns");
    const cancelBtn = button("amem-btn", t.cancel, cancel);
    if (kind === "deleteAll") {
      box.append(el("p", undefined, t.confirmDeleteAll(this.memories.length)));
      btns.append(cancelBtn, button("amem-btn danger-fill", t.confirmDelete, () => void this.deleteAll()));
    } else {
      box.append(el("p", undefined, t.unsaved));
      btns.append(cancelBtn, button("amem-btn primary", t.discard, () => this.close()));
    }
    box.append(btns);
    layer.onclick = cancel;
    layer.append(box);
    cancelBtn.focus();
  }
}

const CSS = `
.amem-ov { position: absolute; inset: 0; z-index: 40; display: flex; flex-direction: column; background: var(--aimbase-bg); color: var(--aimbase-fg); }
.amem-head { display: flex; align-items: center; gap: 8px; padding: 12px 14px; border-bottom: 1px solid var(--aimbase-border); flex-shrink: 0; }
.amem-title { flex: 1; margin: 0; font-size: 1em; font-weight: 600; }
.amem-x, .amem-del { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; flex-shrink: 0; padding: 0; border: 0; border-radius: 6px; background: transparent; color: var(--aimbase-muted); cursor: pointer; }
.amem-x:hover { background: var(--aimbase-chip-hover); color: var(--aimbase-fg); }
.amem-del:hover:not(:disabled) { background: var(--aimbase-chip-hover); color: var(--aimbase-danger); }
.amem-del:disabled { opacity: .5; cursor: default; }
.amem-body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 22px; }
.amem-sec { display: flex; flex-direction: column; gap: 8px; }
.amem-sec h3 { margin: 0; font-size: .95em; font-weight: 600; }
.amem-sechead { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.amem-hint { margin: 0; color: var(--aimbase-muted); font-size: .85em; line-height: 1.45; }
.amem-ta { width: 100%; box-sizing: border-box; min-height: 104px; resize: vertical; padding: 8px 10px; border: 1px solid var(--aimbase-border); border-radius: var(--aimbase-radius); background: var(--aimbase-bg); color: var(--aimbase-fg); font: inherit; font-size: .9em; line-height: 1.5; }
.amem-ta:focus { outline: none; border-color: var(--aimbase-border-strong); }
.amem-foot { display: flex; align-items: center; gap: 10px; }
.amem-err { flex: 1; color: var(--aimbase-danger); font-size: .82em; }
.amem-count { color: var(--aimbase-muted); font-size: .82em; font-variant-numeric: tabular-nums; }
.amem-count.over { color: var(--aimbase-danger); }
.amem-btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 5px 12px; border: 1px solid var(--aimbase-border); border-radius: 6px; background: var(--aimbase-bg); color: var(--aimbase-fg); font: inherit; font-size: .85em; cursor: pointer; }
.amem-btn:hover:not(:disabled) { background: var(--aimbase-chip-bg); }
.amem-btn:disabled { opacity: .5; cursor: default; }
.amem-btn.primary { border-color: var(--aimbase-primary); background: var(--aimbase-primary); color: var(--aimbase-on-primary); }
.amem-btn.primary:hover:not(:disabled) { background: var(--aimbase-primary-hover); }
.amem-btn.danger { color: var(--aimbase-danger); }
.amem-btn.danger-fill { border-color: var(--aimbase-danger); background: var(--aimbase-danger); color: var(--aimbase-on-primary); }
.amem-btn.danger-fill:hover:not(:disabled) { background: var(--aimbase-danger); opacity: .9; }
.amem-switch { position: relative; width: 34px; height: 20px; flex-shrink: 0; padding: 0; border: 0; border-radius: 10px; background: var(--aimbase-border-strong); cursor: pointer; transition: background .15s; }
.amem-switch[aria-checked="true"] { background: var(--aimbase-primary); }
.amem-switch:disabled { opacity: .6; cursor: default; }
.amem-switch span { position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%; background: var(--aimbase-bg); transition: transform .15s; }
.amem-switch[aria-checked="true"] span { transform: translateX(14px); }
.amem-off { padding: 6px 10px; border-radius: 6px; background: var(--aimbase-chip-bg); color: var(--aimbase-muted); font-size: .85em; }
.amem-list { list-style: none; margin: 0; padding: 0; border: 1px solid var(--aimbase-border); border-radius: var(--aimbase-radius); }
.amem-item { display: flex; align-items: flex-start; gap: 8px; padding: 8px 6px 8px 10px; }
.amem-item + .amem-item { border-top: 1px solid var(--aimbase-border); }
.amem-mtext { flex: 1; min-width: 0; font-size: .9em; line-height: 1.45; overflow-wrap: anywhere; white-space: pre-wrap; }
.amem-mdate { display: block; margin-top: 2px; color: var(--aimbase-muted); font-size: .85em; white-space: normal; }
.amem-empty { margin: 0; padding: 14px 0; text-align: center; color: var(--aimbase-muted); font-size: .88em; }
.amem-tail { display: flex; justify-content: flex-end; }
.amem-loading { display: flex; align-items: center; gap: 8px; padding: 6px 0; color: var(--aimbase-muted); font-size: .88em; }
.amem-spin { width: 12px; height: 12px; border: 2px solid var(--aimbase-border); border-top-color: var(--aimbase-muted); border-radius: 50%; animation: amem-spin .8s linear infinite; flex-shrink: 0; }
.amem-btn.primary .amem-spin { border-color: var(--aimbase-on-primary); border-top-color: transparent; }
@keyframes amem-spin { to { transform: rotate(360deg); } }
.amem-fail { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 32px 0; color: var(--aimbase-muted); }
.amem-fail p { margin: 0; }
.amem-cov { position: absolute; inset: 0; z-index: 1; display: flex; align-items: center; justify-content: center; padding: 16px; background: rgba(0, 0, 0, .35); }
.amem-cov[hidden] { display: none; }
.amem-confirm { width: min(100%, 300px); box-sizing: border-box; display: flex; flex-direction: column; gap: 14px; padding: 16px; border-radius: var(--aimbase-radius); background: var(--aimbase-bg); box-shadow: 0 8px 24px rgba(0, 0, 0, .2); }
.amem-confirm p { margin: 0; font-size: .92em; line-height: 1.5; }
.amem-confirm-btns { display: flex; justify-content: flex-end; gap: 8px; }
`;

export const memory: WidgetFeature = {
  id: "memory",
  css: CSS,
  install(ctx) {
    new Memory(ctx).install();
  },
};
