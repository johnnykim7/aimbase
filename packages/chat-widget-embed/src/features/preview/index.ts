import type { AnswerView, SidePaneSpec, WidgetContext, WidgetFeature } from "../../frame/context";
import { svgIcon } from "../../icons";
import { renderRichInto } from "../../rich-render";
import type { ChatArtifact } from "../../types";
import { WidgetApiError } from "../../widget-api";
import { decorateCodeBlocks, svgUrl } from "./blocks";
import { createRunFrame, safeSources, selectionFromFrame } from "./html-frame";
import {
  downloadPath,
  extensionOf,
  infoPath,
  pagePath,
  sameTarget,
  targetName,
  type PreviewArtifact,
  type PreviewInfo,
  type PreviewTarget,
} from "./model";
import { renderScroll, renderSlides, type PageSource, type PagesHandle } from "./pages-view";
import { startPresentation } from "./present";
import { watchSelection } from "./selection";
import { renderSheets } from "./sheet-view";
import { PREVIEW_STRINGS, type PreviewTexts } from "./strings";
import { PREVIEW_CSS } from "./styles";

export type { PreviewTarget } from "./model";

export interface PreviewToolSpec {
  id: string;
  order: number;
  render: (target: PreviewTarget) => HTMLElement | null;
}

const PANE_ID = "preview";
const DOWNLOAD_ICON =
  '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>';
const PRESENT_ICON = '<path d="M2 3h20"/><path d="M21 3v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V3"/><path d="m7 21 5-5 5 5"/>';
const RERUN_ICON =
  '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>';

type Loaded =
  | { state: "loading" }
  | { state: "message"; text: string }
  | { state: "pages"; info: PreviewInfo; src: PageLoader; slides: boolean }
  | { state: "sheet"; info: PreviewInfo }
  | { state: "html"; source: string }
  | { state: "image"; url: string; owned: boolean; vector: boolean }
  | { state: "markdown"; text: string }
  | { state: "text"; text: string };

/** 쪽 그림을 한 번만 받고, 대상을 떠나면 받은 주소를 모두 푼다. */
class PageLoader implements PageSource {
  private readonly cache = new Map<number, Promise<string>>();
  private readonly urls: string[] = [];
  private disposed = false;

  constructor(
    private readonly ctx: WidgetContext,
    private readonly target: Extract<PreviewTarget, { kind: "file" }>,
    readonly count: number,
    readonly width: number,
    readonly height: number,
    private readonly modifiedAt: string,
  ) {}

  load(page: number): Promise<string> {
    let p = this.cache.get(page);
    if (!p) {
      p = this.ctx.api.requestBlob("GET", pagePath(this.target, page, this.modifiedAt)).then((blob) => {
        const url = URL.createObjectURL(blob);
        if (this.disposed) URL.revokeObjectURL(url);
        else this.urls.push(url);
        return url;
      });
      p.catch(() => this.cache.delete(page));
      this.cache.set(page, p);
    }
    return p;
  }

  dispose(): void {
    this.disposed = true;
    this.urls.splice(0).forEach((u) => URL.revokeObjectURL(u));
    this.cache.clear();
  }
}

/*
 * CR-181 이 자기 install 에서 먼저 등록해도 잃지 않게 등록은 모듈에 둔다.
 * 위젯이 한 페이지에 둘이면 내보내는 함수는 마지막에 뜬 위젯의 옆 창을 다룬다(CR-180 11절).
 */
const registry = {
  tools: [] as PreviewToolSpec[],
  footer: null as ((target: PreviewTarget, foot: HTMLElement) => void) | null,
  change: new Set<(target: PreviewTarget | null) => void>(),
  selection: new Set<(text: string) => void>(),
};
let active: PreviewController | null = null;

function emit<T>(listeners: Set<(v: T) => void>, value: T): void {
  for (const l of [...listeners]) {
    try {
      l(value);
    } catch (e) {
      console.error("[aimbase-chat] preview listener failed", e);
    }
  }
}

function sameContent(a: PreviewTarget, b: PreviewTarget): boolean {
  if (a.version !== b.version) return false;
  if (a.kind === "file" && b.kind === "file") return !b.modifiedAt || a.modifiedAt === b.modifiedAt;
  if (a.kind === "block" && b.kind === "block") return a.source === b.source;
  return false;
}

class PreviewController {
  private readonly t: PreviewTexts;
  private target: PreviewTarget | null = null;
  private loaded: Loaded = { state: "loading" };
  private page = 1;
  private htmlMode: "run" | "code" = "run";
  private fit = true;
  private busy = false;
  private gen = 0;
  private openedFor: PreviewTarget | null = null;
  private bodyEl: HTMLElement | null = null;
  private toolsEl: HTMLElement | null = null;
  private pageLabel: HTMLElement | null = null;
  private view: PagesHandle | null = null;
  private cleanups: Array<() => void> = [];

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(PREVIEW_STRINGS);
  }

  current(): PreviewTarget | null {
    return this.isOpen() && this.target ? { ...this.target } : null;
  }

  isOpen(): boolean {
    return this.target !== null && this.ctx.ui.sidePane.currentId() === PANE_ID;
  }

  open(target: PreviewTarget): void {
    const unchanged = this.isOpen() && sameTarget(this.target, target) && sameContent(this.target!, target);
    const keepPage = sameTarget(this.target, target);
    this.target = { ...target };
    if (unchanged) return;
    if (!keepPage) {
      this.page = 1;
      this.htmlMode = "run";
      this.fit = true;
    }
    emit(registry.change, this.current() ?? { ...target });
    void this.load();
  }

  refresh(): void {
    if (this.isOpen()) void this.load();
  }

  close(): void {
    if (this.ctx.ui.sidePane.currentId() === PANE_ID) this.ctx.ui.sidePane.close();
  }

  setBusy(busy: boolean): void {
    this.busy = busy;
    this.applyBusy();
  }

  repaintTools(): void {
    if (this.isOpen()) this.paintTools();
  }

  /** 아래 줄은 틀이 창을 열 때만 그리므로 나중에 붙으면 창을 다시 연다. */
  reopen(): void {
    if (!this.isOpen() || !this.target) return;
    this.openedFor = null;
    this.show();
  }

  /** 답이 끝났을 때 열린 파일이 바뀌었으면 새로 불러온다. 닫힌 옆 창은 열지 않는다(CR-180 4.3 2번). */
  answerDone(a: AnswerView): void {
    const target = this.target;
    if (!this.isOpen() || !target || target.kind !== "file" || target.sessionId !== a.sessionId) return;
    const art = (a.artifacts as PreviewArtifact[]).find((f) => f.name === target.name);
    if (!art?.modified_at || art.modified_at === target.modifiedAt) return;
    this.target = { ...target, modifiedAt: art.modified_at };
    void this.load();
  }

  relocale(): void {
    if (this.isOpen()) this.paint();
  }

  dispose(): void {
    this.close();
    this.closed();
  }

  private async load(): Promise<void> {
    const target = this.target;
    if (!target) return;
    const gen = ++this.gen;
    this.release(this.loaded);
    this.loaded = { state: "loading" };
    this.show();
    let next: Loaded;
    try {
      next = await this.fetch(target);
    } catch (e) {
      next = { state: "message", text: this.errorText(e) };
    }
    if (gen !== this.gen) {
      this.release(next);
      return;
    }
    this.loaded = next;
    this.show();
  }

  private async fetch(target: PreviewTarget): Promise<Loaded> {
    if (target.kind === "block") {
      return target.lang === "html"
        ? { state: "html", source: target.source }
        : { state: "image", url: svgUrl(target.source), owned: false, vector: true };
    }
    const info = await this.ctx.api.request<PreviewInfo>("GET", infoPath(target));
    if (this.target?.kind === "file" && sameTarget(this.target, target)) {
      this.target = { ...this.target, modifiedAt: info.modified_at };
    }
    switch (info.preview_kind) {
      case "PAGES": {
        const count = info.page_count ?? 0;
        if (count < 1) return { state: "message", text: this.t.unsupported };
        const src = new PageLoader(this.ctx, target, count, info.page_width ?? 0, info.page_height ?? 0, info.modified_at);
        return { state: "pages", info, src, slides: extensionOf(info.name) === "pptx" };
      }
      case "SHEET":
        return { state: "sheet", info };
      case "HTML":
        return { state: "html", source: await (await this.download(target)).text() };
      case "MARKDOWN":
        return { state: "markdown", text: await (await this.download(target)).text() };
      case "TEXT":
        return { state: "text", text: await (await this.download(target)).text() };
      case "SVG": {
        const blob = await this.download(target);
        return {
          state: "image",
          url: URL.createObjectURL(new Blob([blob], { type: "image/svg+xml" })),
          owned: true,
          vector: true,
        };
      }
      case "IMAGE":
        return { state: "image", url: URL.createObjectURL(await this.download(target)), owned: true, vector: false };
      default:
        return { state: "message", text: this.reasonText(info.reason) };
    }
  }

  private download(target: Extract<PreviewTarget, { kind: "file" }>): Promise<Blob> {
    return this.ctx.api.requestBlob("GET", downloadPath(target));
  }

  private reasonText(reason: string | undefined): string {
    if (reason === "too_large") return this.t.tooLarge;
    if (reason === "too_many_pages") return this.t.tooManyPages;
    return this.t.unsupported;
  }

  private errorText(e: unknown): string {
    if (e instanceof WidgetApiError && e.code === "preview_busy") return this.t.busy;
    return this.t.failed;
  }

  private release(l: Loaded): void {
    if (l.state === "pages") l.src.dispose();
    if (l.state === "image" && l.owned) URL.revokeObjectURL(l.url);
  }

  /** 대상이 바뀌면 머리줄 제목이 바뀌어 창을 다시 열고, 같은 대상이면 아래 줄을 지우지 않게 본문만 다시 그린다. */
  private show(): void {
    const target = this.target;
    if (!target) return;
    if (this.bodyEl && this.isOpen() && this.openedFor && sameTarget(this.openedFor, target)) {
      this.paint();
      return;
    }
    this.openedFor = { ...target };
    this.ctx.ui.sidePane.open(this.spec());
  }

  private spec(): SidePaneSpec {
    const tools = document.createElement("span");
    tools.className = "pv-htools";
    this.toolsEl = tools;
    return {
      id: PANE_ID,
      title: () => (this.target ? targetName(this.target) : ""),
      tools: [tools],
      render: (body) => {
        this.bodyEl = body;
        this.paint();
      },
      footer: registry.footer
        ? (foot) => {
            if (this.target) registry.footer?.({ ...this.target }, foot);
          }
        : undefined,
      onClose: () => this.closed(),
    };
  }

  private closed(): void {
    this.gen++;
    this.disposeView();
    this.release(this.loaded);
    this.loaded = { state: "loading" };
    const wasOpen = this.target !== null;
    this.target = null;
    this.openedFor = null;
    this.bodyEl = null;
    this.toolsEl = null;
    if (wasOpen) emit(registry.change, null);
  }

  private disposeView(): void {
    this.view?.dispose();
    this.view = null;
    this.cleanups.splice(0).forEach((fn) => fn());
  }

  private paint(): void {
    const body = this.bodyEl;
    if (!body) return;
    this.disposeView();
    const root = document.createElement("div");
    root.className = "pv-root";
    body.replaceChildren(root);
    const l = this.loaded;
    switch (l.state) {
      case "loading":
        root.appendChild(this.stateBox(this.t.loading, true));
        break;
      case "message":
        root.appendChild(this.stateBox(l.text, false));
        break;
      case "pages":
        this.paintPages(root, l.src, l.slides);
        break;
      case "sheet":
        this.watch(renderSheets(root, l.info.sheets ?? [], this.t));
        break;
      case "html":
        this.paintHtml(root, l.source);
        break;
      case "image":
        this.paintImage(root, l.url, l.vector);
        break;
      case "markdown": {
        const md = document.createElement("div");
        md.className = "pv-md";
        root.appendChild(md);
        renderRichInto(md, l.text);
        this.watch(md);
        break;
      }
      case "text": {
        const pre = document.createElement("pre");
        pre.className = "pv-text";
        pre.textContent = this.prettyText(l.text);
        root.appendChild(pre);
        this.watch(pre);
        break;
      }
    }
    this.paintTools();
    this.applyBusy();
  }

  private prettyText(text: string): string {
    if (this.target?.kind === "file" && extensionOf(this.target.name) === "json") {
      try {
        return JSON.stringify(JSON.parse(text), null, 2);
      } catch {
        return text;
      }
    }
    return text;
  }

  private watch(el: HTMLElement): void {
    this.cleanups.push(watchSelection(el, (text) => emit(registry.selection, text)));
  }

  private stateBox(text: string, spinning: boolean): HTMLElement {
    const box = document.createElement("div");
    box.className = "pv-state";
    if (spinning) {
      const spin = document.createElement("span");
      spin.className = "pv-spin";
      box.appendChild(spin);
    }
    const msg = document.createElement("p");
    msg.textContent = text;
    box.appendChild(msg);
    if (!spinning && this.target?.kind === "file") {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "pv-btn";
      b.innerHTML = svgIcon(DOWNLOAD_ICON, 14);
      const label = document.createElement("span");
      label.textContent = this.t.download;
      b.appendChild(label);
      b.addEventListener("click", () => this.downloadCurrent());
      box.appendChild(b);
    }
    return box;
  }

  private paintPages(root: HTMLElement, src: PageLoader, slides: boolean): void {
    const onPage = (n: number) => {
      this.page = n;
      if (this.pageLabel) this.pageLabel.textContent = this.t.pageOf(n, src.count);
    };
    if (slides) {
      this.view = renderSlides(root, src, this.page, this.t, onPage);
    } else {
      const start = this.page;
      this.view = renderScroll(root, src, this.t, onPage);
      if (start > 1) requestAnimationFrame(() => this.view?.go(start));
    }
  }

  private paintHtml(root: HTMLElement, source: string): void {
    const bar = document.createElement("div");
    bar.className = "pv-codebar pv-subbar";
    const seg = document.createElement("div");
    seg.className = "pv-seg";
    const run = this.barButton(this.t.run, this.htmlMode === "run");
    const code = this.barButton(this.t.code, this.htmlMode === "code");
    seg.append(run, code);
    bar.appendChild(seg);
    run.addEventListener("click", () => this.setHtmlMode("run"));
    code.addEventListener("click", () => this.setHtmlMode("code"));
    const content = document.createElement("div");
    content.className = "pv-htmlbody";
    root.append(bar, content);

    if (this.htmlMode === "code") {
      const pre = document.createElement("pre");
      pre.className = "pv-text";
      pre.textContent = source;
      content.appendChild(pre);
      this.watch(pre);
      return;
    }
    const rerun = this.barButton(this.t.rerun, false);
    rerun.insertAdjacentHTML("afterbegin", svgIcon(RERUN_ICON, 13));
    bar.appendChild(rerun);
    const sources = safeSources((this.ctx.widgetSettings() as Record<string, unknown> | null)?.preview_html_script_sources);
    let frame = createRunFrame(source, sources);
    content.appendChild(frame);
    rerun.addEventListener("click", () => {
      const fresh = createRunFrame(source, sources);
      frame.replaceWith(fresh);
      frame = fresh;
    });
    const onMessage = (e: MessageEvent) => {
      const text = selectionFromFrame(e, frame);
      if (text) emit(registry.selection, text);
    };
    window.addEventListener("message", onMessage);
    this.cleanups.push(() => window.removeEventListener("message", onMessage));
  }

  private setHtmlMode(mode: "run" | "code"): void {
    if (this.htmlMode === mode) return;
    this.htmlMode = mode;
    this.paint();
  }

  private paintImage(root: HTMLElement, url: string, vector: boolean): void {
    const wrap = document.createElement("div");
    wrap.className = "pv-imgwrap";
    wrap.classList.toggle("pv-vector", vector);
    const img = document.createElement("img");
    img.alt = "";
    img.src = url;
    const apply = () => {
      wrap.classList.toggle("pv-actual", !this.fit);
      // 말풍선 도움말은 요소 위에 떠 옆 창 머리줄에 가리므로 기본 도움말을 쓴다.
      wrap.title = this.fit ? this.t.actualSize : this.t.fitSize;
      wrap.setAttribute("aria-label", wrap.title);
    };
    wrap.addEventListener("click", () => {
      this.fit = !this.fit;
      apply();
    });
    wrap.appendChild(img);
    root.appendChild(wrap);
    apply();
  }

  private barButton(label: string, on: boolean): HTMLButtonElement {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "pv-barbtn";
    b.classList.toggle("on", on);
    const span = document.createElement("span");
    span.textContent = label;
    b.appendChild(span);
    return b;
  }

  /** 머리줄: 쪽 표시, CR-181 단추, 발표, 다운로드 순(CR-180 4.1 4번). */
  private paintTools(): void {
    const tools = this.toolsEl;
    const target = this.target;
    if (!tools || !target) return;
    tools.replaceChildren();
    this.pageLabel = null;
    const l = this.loaded;
    if (l.state === "pages") {
      const label = document.createElement("span");
      label.className = "pv-hcount";
      label.textContent = this.t.pageOf(this.page, l.src.count);
      this.pageLabel = label;
      tools.appendChild(label);
    }
    for (const spec of registry.tools) {
      try {
        const el = spec.render({ ...target });
        if (el) tools.appendChild(el);
      } catch (e) {
        console.error(`[aimbase-chat] preview tool ${spec.id} failed`, e);
      }
    }
    if (l.state === "pages") {
      const present = this.headButton(PRESENT_ICON, this.t.present);
      present.addEventListener("click", () => this.present(l.src));
      tools.appendChild(present);
    }
    if (target.kind === "file") {
      const dl = this.headButton(DOWNLOAD_ICON, this.t.download);
      dl.addEventListener("click", () => this.downloadCurrent());
      tools.appendChild(dl);
    }
  }

  private headButton(paths: string, label: string): HTMLButtonElement {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "ib sm";
    b.innerHTML = svgIcon(paths, 16);
    b.setAttribute("aria-label", label);
    b.setAttribute("data-tip", label);
    return b;
  }

  private present(src: PageLoader): void {
    startPresentation({
      layer: this.ctx.ui.layer,
      src,
      start: this.view?.current() ?? this.page,
      pageOf: (n, total) => this.t.pageOf(n, total),
      onEscape: (fn) => this.ctx.ui.onEscape(fn),
      onEnd: (last) => {
        this.page = last;
        this.view?.go(last);
        if (this.pageLabel) this.pageLabel.textContent = this.t.pageOf(last, src.count);
      },
    });
  }

  private applyBusy(): void {
    const root = this.bodyEl?.querySelector(".pv-root");
    if (!root) return;
    root.querySelector(".pv-busy")?.remove();
    if (!this.busy) return;
    const cover = document.createElement("div");
    cover.className = "pv-busy";
    const spin = document.createElement("span");
    spin.className = "pv-spin";
    cover.appendChild(spin);
    root.appendChild(cover);
  }

  private downloadCurrent(): void {
    const target = this.target;
    if (target?.kind !== "file") return;
    void saveFile(this.ctx, downloadPath(target), target.name, this.t);
  }
}

async function saveFile(ctx: WidgetContext, path: string, name: string, t: PreviewTexts): Promise<void> {
  try {
    const blob = await ctx.api.requestBlob("GET", path);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    // 브라우저가 내려받기를 시작할 시간을 준 뒤 푼다.
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  } catch {
    ctx.ui.toast(t.downloadFailed);
  }
}

function previewable(f: ChatArtifact): boolean {
  const kind = (f as PreviewArtifact).preview_kind;
  return !!kind && kind !== "NONE";
}

export function openPreview(target: PreviewTarget): void {
  active?.open(target);
}

export function currentPreview(): PreviewTarget | null {
  return active?.current() ?? null;
}

export function onPreviewChange(listener: (target: PreviewTarget | null) => void): () => void {
  registry.change.add(listener);
  return () => registry.change.delete(listener);
}

export function refreshPreview(): void {
  active?.refresh();
}

export function addPreviewTool(spec: PreviewToolSpec): void {
  registry.tools = [...registry.tools.filter((s) => s.id !== spec.id), spec].sort((a, b) => a.order - b.order);
  active?.repaintTools();
}

export function setPreviewFooter(render: ((target: PreviewTarget, foot: HTMLElement) => void) | null): void {
  registry.footer = render;
  active?.reopen();
}

export function setPreviewBusy(busy: boolean): void {
  active?.setBusy(busy);
}

export function onPreviewSelection(listener: (text: string) => void): () => void {
  registry.selection.add(listener);
  return () => registry.selection.delete(listener);
}

export const preview: WidgetFeature = {
  id: "preview",
  css: PREVIEW_CSS,
  install(ctx) {
    const controller = new PreviewController(ctx);
    active = controller;
    const t = ctx.strings(PREVIEW_STRINGS);

    ctx.ui.answer.addArtifactOpener({
      id: "preview",
      order: 10,
      canOpen: previewable,
      open: (f, a) =>
        controller.open({
          kind: "file",
          sessionId: a.sessionId,
          name: f.name,
          modifiedAt: (f as PreviewArtifact).modified_at,
        }),
    });

    ctx.ui.answer.addArtifactAction({
      id: "preview-download",
      order: 10,
      render: (f) => {
        if (!previewable(f)) return null;
        const b = document.createElement("button");
        b.type = "button";
        b.className = "pv-chipdl";
        b.innerHTML = svgIcon(DOWNLOAD_ICON, 13);
        b.setAttribute("aria-label", t.download);
        b.setAttribute("data-tip", t.download);
        b.addEventListener("click", () => void saveFile(ctx, f.download_url, f.name, t));
        return b;
      },
    });

    ctx.ui.answer.addDecorator({
      id: "preview-code",
      order: 10,
      decorate: (body, a) => decorateCodeBlocks(body, a, t, (target) => controller.open(target)),
    });

    ctx.on("answerDone", (a) => controller.answerDone(a));
    ctx.on("newConversation", () => controller.close());
    ctx.on("localeChange", () => controller.relocale());
    ctx.on("destroy", () => {
      controller.dispose();
      if (active === controller) active = null;
    });
  },
};
