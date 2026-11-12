import type { AnswerView, WidgetContext, WidgetFeature } from "../../frame/context";
import { icon, svgIcon } from "../../icons";
import { WidgetApiError } from "../../widget-api";
import {
  addPreviewTool,
  currentPreview,
  onPreviewChange,
  onPreviewSelection,
  openPreview,
  refreshPreview,
  setPreviewBusy,
  setPreviewFooter,
  type PreviewTarget,
  type PreviewToolSpec,
} from "../preview";
import {
  blockVersion,
  hasSource,
  listDocuments,
  readResult,
  restoreVersion,
  type DocInfo,
  type DocResult,
} from "./api";
import { DOCUMENT_EDIT_STRINGS, type DocumentEditTexts } from "./strings";
import { DOCUMENT_EDIT_CSS } from "./styles";

const QUOTE_ICON =
  '<path d="M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z"/><path d="M5 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z"/>';
const FILE_ICON =
  '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/>';
const TEXTAREA_MAX_PX = 180;

type FileTarget = Extract<PreviewTarget, { kind: "file" }>;
type BlockTarget = Extract<PreviewTarget, { kind: "block" }>;

function targetKey(t: PreviewTarget | null): string {
  if (!t) return "";
  return t.kind === "file" ? `f\u0000${t.sessionId}\u0000${t.name}` : `b\u0000${t.turnId}\u0000${t.index}`;
}

function docFor(docs: DocInfo[], t: PreviewTarget): DocInfo | undefined {
  return t.kind === "file"
    ? docs.find((d) => d.kind === "FILE" && d.name === t.name)
    : docs.find((d) => d.kind === "BLOCK" && d.turn_id === t.turnId && d.index === t.index);
}

/** 되감기로 지운 판은 목록에 없다. 마지막 판 번호는 남은 판에서 센다. */
function latestOf(doc: DocInfo): number {
  return doc.versions.reduce((m, v) => Math.max(m, v.version), 0) || doc.latest_version;
}

/** 판 없이 연 파일은 작업 폴더의 지금 파일(마지막 판)이고, 판 없이 연 코드 블록은 답에 나온 원래 글(1판)이다. */
function viewVersion(t: PreviewTarget, doc: DocInfo | null): number {
  if (t.version !== undefined) return t.version;
  if (t.kind === "file") return doc ? latestOf(doc) : 1;
  return 1;
}

function docLabel(doc: DocInfo): string {
  return doc.kind === "FILE" ? doc.name ?? "" : (doc.lang ?? "").toUpperCase();
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

class DocumentEdit {
  private readonly t: DocumentEditTexts;
  private readonly docsCache = new Map<string, Promise<DocInfo[]>>();
  private target: PreviewTarget | null = null;
  private doc: DocInfo | null = null;
  private editable = false;
  private selection = "";
  private editing = false;
  private restoring = false;
  private gen = 0;

  private readonly root = el("div", "de-foot");
  private readonly oldRow = el("div", "de-old");
  private readonly oldMsg = el("span", "de-msg");
  private readonly restoreBtn = el("button", "de-link");
  private readonly latestBtn = el("button", "de-link");
  private readonly chip = el("div", "de-chip");
  private readonly chipText = el("span", "de-q");
  private readonly chipClear = el("button");
  private readonly input = el("textarea");
  private readonly sendBtn = el("button", "send");
  private readonly toolSpec: PreviewToolSpec;

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(DOCUMENT_EDIT_STRINGS);
    this.toolSpec = { id: "document-versions", order: 10, render: (target) => this.renderPicker(target) };
    this.build();
  }

  install(): Array<() => void> {
    const ctx = this.ctx;
    setPreviewFooter((_target, foot) => {
      foot.appendChild(this.root);
      this.paint();
    });
    addPreviewTool(this.toolSpec);
    ctx.ui.answer.addDecorator({ id: "document-edit", order: 50, decorate: (body, a) => this.decorate(body, a) });
    ctx.ui.addErrorTexts({
      "document_edit_invalid:target": () => this.t.errInvalid,
      "document_edit_invalid:no_session": () => this.t.errInvalid,
      "document_edit_invalid:source": () => this.t.errSource,
      "document_edit_invalid:base_version": () => this.t.errBaseVersion,
      document_not_found: () => this.t.errNotFound,
      document_forbidden: () => this.t.errForbidden,
      document_source_gone: () => this.t.errSourceGone,
      document_edit_unsupported: () => this.t.errUnsupported,
    });
    return [
      onPreviewChange((target) => this.changed(target)),
      onPreviewSelection((text) => this.selected(text)),
      ctx.on("answerStart", (a) => this.answerStart(a)),
      ctx.on("answerDone", (a) => void this.answerDone(a)),
      ctx.on("newConversation", () => this.reset()),
      ctx.on("localeChange", () => this.paint()),
    ];
  }

  private build(): void {
    this.restoreBtn.type = "button";
    this.restoreBtn.addEventListener("click", () => void this.restore());
    this.latestBtn.type = "button";
    this.latestBtn.addEventListener("click", () => {
      if (this.doc) void this.openVersion(this.doc, latestOf(this.doc));
    });
    this.oldRow.append(this.oldMsg, this.restoreBtn, this.latestBtn);

    this.chipClear.type = "button";
    this.chipClear.innerHTML = icon("x", 12);
    this.chipClear.addEventListener("click", () => {
      this.selection = "";
      this.paint();
      this.input.focus();
    });
    this.chip.innerHTML = svgIcon(QUOTE_ICON, 12);
    this.chip.append(this.chipText, this.chipClear);

    this.input.rows = 1;
    this.input.addEventListener("input", () => {
      this.fitInput();
      this.paintSend();
    });
    this.input.addEventListener("keydown", (e) => {
      if (e.key !== "Enter" || e.shiftKey || e.isComposing || e.keyCode === 229) return;
      e.preventDefault();
      void this.send();
    });
    this.sendBtn.type = "button";
    this.sendBtn.innerHTML = icon("up", 16);
    this.sendBtn.addEventListener("click", () => void this.send());

    const comp = el("div", "de-comp");
    comp.append(this.input, this.sendBtn);
    const box = el("div", "de-in");
    box.append(this.chip, comp);
    this.root.append(this.oldRow, box);
    this.root.hidden = true;
  }

  private fitInput(): void {
    this.input.style.height = "auto";
    this.input.style.height = `${Math.min(this.input.scrollHeight, TEXTAREA_MAX_PX)}px`;
  }

  private sessionOf(t: PreviewTarget): string {
    return t.kind === "file" ? t.sessionId : this.ctx.sessionId();
  }

  private docs(sessionId: string, fresh = false): Promise<DocInfo[]> {
    let p = fresh ? undefined : this.docsCache.get(sessionId);
    if (!p) {
      p = listDocuments(this.ctx, sessionId).catch((e) => {
        this.docsCache.delete(sessionId);
        throw e;
      });
      this.docsCache.set(sessionId, p);
    }
    return p;
  }

  /** 대상이 바뀌거나 판이 바뀔 때마다 문서 목록을 새로 받는다. 되감기로 판이 지워졌을 수 있다. */
  private changed(target: PreviewTarget | null): void {
    if (targetKey(target) !== targetKey(this.target)) {
      this.selection = "";
      this.input.value = "";
      this.fitInput();
    } else if (target?.version !== this.target?.version) {
      this.selection = "";
    }
    this.target = target;
    const gen = ++this.gen;
    if (!target) {
      this.doc = null;
      this.editable = false;
      return;
    }
    void this.resolve(target, gen);
  }

  private async resolve(target: PreviewTarget, gen: number): Promise<void> {
    const [docs, editable] = await Promise.all([
      this.docs(this.sessionOf(target), true).catch(() => [] as DocInfo[]),
      this.canEdit(target).catch(() => false),
    ]);
    if (gen !== this.gen) return;
    if (target.kind === "block" && target.version === undefined && (await this.openEditedBlock(target, docs, gen))) return;
    if (gen !== this.gen) return;
    this.doc = docFor(docs, target) ?? null;
    this.editable = editable;
    this.paint();
  }

  /**
   * 코드 블록을 고친 답에도 새 코드 블록이 나온다. 그 블록을 열면 새 문서가 아니라 원래 문서의 그 판을 연다.
   * 답 번호가 그 판을 만든 답이고 글이 같을 때만 옮긴다.
   */
  private async openEditedBlock(target: BlockTarget, docs: DocInfo[], gen: number): Promise<boolean> {
    for (const d of docs) {
      if (d.kind !== "BLOCK" || d.lang !== target.lang) continue;
      const v = d.versions.find((x) => x.made_by === "CHAT_EDIT" && x.turn_id === target.turnId);
      if (!v) continue;
      const body = await blockVersion(this.ctx, d.document_id, v.version).catch(() => null);
      if (gen !== this.gen) return true;
      if (!body || body.content.trim() !== target.source.trim()) continue;
      this.openBlock(d, v.version, body.content);
      return true;
    }
    return false;
  }

  private async canEdit(target: PreviewTarget): Promise<boolean> {
    if (target.kind === "block") return target.lang === "html" || target.lang === "svg";
    if (target.sessionId !== this.ctx.sessionId()) return false;
    const settings = this.ctx.widgetSettings() as unknown as Record<string, unknown> | null;
    // 원본 있는 파일은 파일 만들기로 다시 만들므로 그 설정이 꺼지면 서버가 고치지 않는다(BIZ-203)
    if (await hasSource(this.ctx, { ...target, version: undefined })) return settings?.["file_create_enabled"] !== false;
    return settings?.["document_binary_edit_supported"] === true;
  }

  private selected(text: string): void {
    const value = text.trim();
    if (!value || !this.editable || !this.target) return;
    this.selection = value;
    this.paint();
  }

  private reset(): void {
    this.docsCache.clear();
    if (this.editing || this.restoring) setPreviewBusy(false);
    this.editing = false;
    this.restoring = false;
    this.paint();
  }

  private locked(): boolean {
    return this.editing || this.restoring;
  }

  private paint(): void {
    addPreviewTool(this.toolSpec);
    const t = this.t;
    const target = this.target;
    const show = !!target && this.editable && targetKey(target) === targetKey(currentPreview());
    this.root.hidden = !show;
    if (!show || !target) return;

    const doc = this.doc;
    const view = viewVersion(target, doc);
    const latest = doc ? latestOf(doc) : view;
    this.oldRow.hidden = !doc || view >= latest;
    this.oldMsg.textContent = t.viewing(view, latest);
    this.restoreBtn.textContent = t.restore;
    this.restoreBtn.disabled = this.locked() || this.ctx.isBusy();
    this.latestBtn.textContent = t.showLatest;
    this.latestBtn.disabled = this.locked();

    this.chip.hidden = !this.selection;
    this.chipText.textContent = t.selected(this.selection.replace(/\s+/g, " "));
    this.chipText.title = this.selection;
    this.chipClear.setAttribute("aria-label", t.clearSelection);
    this.chipClear.setAttribute("data-tip", t.clearSelection);

    this.input.placeholder = this.selection ? t.placeholderSelection : t.placeholder;
    this.sendBtn.setAttribute("aria-label", t.send);
    this.sendBtn.setAttribute("data-tip", t.send);
    this.paintSend();
  }

  private paintSend(): void {
    this.sendBtn.disabled = !this.input.value.trim() || this.locked() || this.ctx.isBusy();
  }

  private renderPicker(target: PreviewTarget): HTMLElement | null {
    const doc = this.doc;
    if (!doc || targetKey(target) !== targetKey(this.target)) return null;
    const versions = doc.versions.map((v) => v.version).sort((a, b) => a - b);
    if (versions.length < 2) return null;
    const view = viewVersion(target, doc);
    const at = versions.indexOf(view);
    const prev = at > 0 ? versions[at - 1] : undefined;
    const next = at >= 0 && at < versions.length - 1 ? versions[at + 1] : undefined;
    const box = el("span", "de-ver");
    box.append(
      this.pickerButton("cleft", this.t.prevVersion, prev, doc),
      el("span", "de-n", `${view} / ${latestOf(doc)}`),
      this.pickerButton("cright", this.t.nextVersion, next, doc),
    );
    return box;
  }

  private pickerButton(name: "cleft" | "cright", label: string, version: number | undefined, doc: DocInfo): HTMLButtonElement {
    const b = el("button");
    b.type = "button";
    b.innerHTML = icon(name, 16);
    b.setAttribute("aria-label", label);
    b.setAttribute("data-tip", label);
    b.disabled = version === undefined || this.locked();
    if (version !== undefined) b.addEventListener("click", () => void this.openVersion(doc, version));
    return b;
  }

  /** 마지막 판 파일은 판 없이 연다. 작업 폴더 파일이 마지막 판이라 변환을 한 번 더 하지 않는다. */
  private async openVersion(doc: DocInfo, version: number): Promise<void> {
    if (doc.kind === "FILE") {
      const sessionId = this.target?.kind === "file" ? this.target.sessionId : this.ctx.sessionId();
      const name = doc.name ?? "";
      const latest = version >= latestOf(doc);
      const current = currentPreview();
      if (latest && current?.kind === "file" && current.name === name && current.version === undefined) {
        refreshPreview();
        this.paint();
        return;
      }
      openPreview({ kind: "file", sessionId, name, version: latest ? undefined : version });
      return;
    }
    try {
      const body = await blockVersion(this.ctx, doc.document_id, version);
      this.openBlock(doc, version, body.content);
    } catch (e) {
      this.ctx.ui.toast(this.errorText(e, this.t.failed));
    }
  }

  private openBlock(doc: DocInfo, version: number, source: string): void {
    if (doc.turn_id === null || doc.index === null) return;
    openPreview({
      kind: "block",
      turnId: doc.turn_id,
      index: doc.index,
      lang: doc.lang === "svg" ? "svg" : "html",
      source,
      version,
    });
  }

  private async send(): Promise<void> {
    const target = this.target;
    const text = this.input.value.trim();
    if (!target || !text || !this.editable || this.locked() || this.ctx.isBusy()) return;
    const doc = this.doc;
    const edit: Record<string, unknown> = { target: this.requestTarget(target, doc) };
    if (doc) edit.base_version = viewVersion(target, doc);
    if (this.selection) edit.selection = { text: this.selection };
    this.input.value = "";
    this.selection = "";
    this.fitInput();
    this.paint();
    await this.ctx.chat.send(text, { body: { document_edit: edit } });
  }

  /** 문서가 이미 있으면 원래 자리로 찾고, 처음 고치는 코드 블록만 본문을 싣는다(1판). */
  private requestTarget(target: PreviewTarget, doc: DocInfo | null): Record<string, unknown> {
    if (target.kind === "file") return { kind: "file", name: target.name };
    if (doc) return { kind: "block", turn_id: doc.turn_id, index: doc.index, lang: doc.lang };
    return { kind: "block", turn_id: target.turnId, index: target.index, lang: target.lang, source: target.source };
  }

  private answerStart(a: AnswerView): void {
    this.docsCache.delete(a.sessionId);
    if (!a.requestBody["document_edit"]) {
      this.paint();
      return;
    }
    this.editing = true;
    setPreviewBusy(true, this.t.editing);
    this.paint();
  }

  private async answerDone(a: AnswerView): Promise<void> {
    this.docsCache.delete(a.sessionId);
    const wasEditing = this.editing;
    this.editing = false;
    if (wasEditing) setPreviewBusy(false);
    this.paint();
    if (!a.requestBody["document_edit"]) return;
    const result = readResult(a.extra);
    const target = this.target;
    if (!target) return;
    const docs = await this.docs(this.sessionOf(target), true).catch(() => null);
    if (!docs || target !== this.target) return;
    const shown = docFor(docs, target);
    const edited = result ? docs.find((d) => d.document_id === result.document_id) : undefined;
    if (result?.status === "UPDATED" && result.version !== undefined && edited && (!shown || shown.document_id === edited.document_id)) {
      this.doc = edited;
      if (edited.kind === "FILE" && this.fileReloadedByPreview(a, edited)) {
        this.paint();
        return;
      }
      await this.openVersion(edited, result.version);
      return;
    }
    this.doc = shown ?? null;
    this.paint();
  }

  /** CR-180 옆 창은 답의 파일 수정 시각이 바뀌면 스스로 다시 부른다. 그때는 또 부르지 않는다. */
  private fileReloadedByPreview(a: AnswerView, doc: DocInfo): boolean {
    const current = currentPreview();
    if (current?.kind !== "file" || current.name !== doc.name || current.version !== undefined) return false;
    const art = a.artifacts.find((f) => f.name === doc.name) as { modified_at?: string } | undefined;
    return !!art?.modified_at;
  }

  private async restore(): Promise<void> {
    const doc = this.doc;
    const target = this.target;
    if (!doc || !target || this.locked() || this.ctx.isBusy()) return;
    const version = viewVersion(target, doc);
    this.restoring = true;
    setPreviewBusy(true, this.t.editing);
    this.paint();
    try {
      const result: DocResult = await restoreVersion(this.ctx, doc.document_id, version);
      if (result.status === "UNCHANGED") this.ctx.ui.toast(this.t.sameAsLatest);
      const docs = await this.docs(this.sessionOf(target), true);
      const fresh = docs.find((d) => d.document_id === doc.document_id);
      if (fresh && target === this.target) {
        this.doc = fresh;
        await this.openVersion(fresh, result.status === "UPDATED" && result.version !== undefined ? result.version : latestOf(fresh));
      }
    } catch (e) {
      this.ctx.ui.toast(this.errorText(e, this.t.restoreFailed));
    } finally {
      this.restoring = false;
      setPreviewBusy(false);
      this.paint();
    }
  }

  private errorText(e: unknown, fallback: string): string {
    if (!(e instanceof WidgetApiError)) return fallback;
    switch (e.code) {
      case "session_busy":
        return this.t.sessionBusy;
      case "document_not_found":
        return this.t.errNotFound;
      case "document_forbidden":
        return this.t.errForbidden;
      case "document_source_gone":
        return this.t.errSourceGone;
      default:
        return fallback;
    }
  }

  /** 고치기 답 본문 끝에 결과를 붙인다. 지난 대화는 그 답이 만든 판을 문서 목록에서 찾아 다시 그린다. */
  private decorate(body: HTMLElement, a: AnswerView): void {
    const result = readResult(a.extra);
    if (!result && !a.turnId) return;
    if (result && result.status !== "UPDATED") {
      const wrap = el("div", "de-result");
      wrap.appendChild(el("span", "de-note", result.status === "UNCHANGED" ? this.t.unchanged : this.t.failed));
      body.appendChild(wrap);
      return;
    }
    const wrap = el("div", "de-result");
    body.appendChild(wrap);
    void this.cardsFor(a, result)
      .then((made) => made.forEach((m) => wrap.appendChild(this.card(m.doc, m.version))))
      .catch(() => undefined);
  }

  /** 받아 둔 목록이 이번 판보다 먼저 받은 것이면 한 번 새로 받는다. */
  private async cardsFor(a: AnswerView, result: DocResult | null): Promise<Array<{ doc: DocInfo; version: number }>> {
    if (result?.version !== undefined) {
      const find = (docs: DocInfo[]) => {
        const doc = docs.find((d) => d.document_id === result.document_id);
        return doc && doc.versions.some((v) => v.version === result.version) ? doc : undefined;
      };
      const doc = find(await this.docs(a.sessionId)) ?? find(await this.docs(a.sessionId, true));
      return doc ? [{ doc, version: result.version }] : [];
    }
    const made: Array<{ doc: DocInfo; version: number }> = [];
    for (const doc of await this.docs(a.sessionId)) {
      for (const v of doc.versions) {
        if (v.made_by === "CHAT_EDIT" && v.turn_id === a.turnId) made.push({ doc, version: v.version });
      }
    }
    return made;
  }

  private card(doc: DocInfo, version: number): HTMLButtonElement {
    const b = el("button", "artifact");
    b.type = "button";
    b.innerHTML = svgIcon(FILE_ICON, 13);
    b.appendChild(el("span", undefined, `${docLabel(doc)} · ${this.t.version(version)}`));
    b.addEventListener("click", () => void this.openVersion(doc, version));
    return b;
  }
}

export const documentEdit: WidgetFeature = {
  id: "document-edit",
  css: DOCUMENT_EDIT_CSS,
  install(ctx) {
    const unsubs = new DocumentEdit(ctx).install();
    ctx.on("destroy", () => unsubs.splice(0).forEach((fn) => fn()));
  },
};
