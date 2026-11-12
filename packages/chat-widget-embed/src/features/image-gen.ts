import type { TokenizerAndRendererExtension, Tokens } from "marked";
import type { AnswerView, ComposerModeSpec, WidgetContext, WidgetFeature } from "../frame/context";
import { defineStrings } from "../frame/i18n";
import { svgIcon } from "../icons";

/**
 * 그림 만들기(CR-184, PRD-543). + 메뉴 「그림 만들기」, 입력칸 모드 「그림 만들기」 「그림 고치기」,
 * 답 글의 그림 참조 `![설명](aimbase-image:<id>)` 를 그림으로 그리고 크게 보기, 다운로드, 그림 고치기 단추를 단다.
 * 그림 생성과 대화 기록은 서버가 한다(T3-2 위젯 이미지 생성). 위젯 본체는 고치지 않는다.
 */

const S = defineStrings({
  ko: {
    create: "그림 만들기",
    edit: "그림 고치기",
    zoom: "크게 보기",
    download: "다운로드",
    close: "닫기",
    loadFailed: "그림을 불러오지 못했습니다",
    notConfigured: "이 연결에는 그림 만들기가 설정되어 있지 않습니다",
    promptEmpty: "그림 설명을 적어 주세요",
    promptTooLong: (n: number) => `그림 설명은 ${n}자까지 쓸 수 있습니다`,
    tooManySources: (n: number) => `바탕 그림은 ${n}장까지 붙일 수 있습니다`,
    sourceInvalid: "그림 모드에는 그림 파일만 붙일 수 있습니다",
    notFound: "고칠 그림을 찾지 못했습니다",
    providerLimit: "그림 서비스 사용 한도를 넘었습니다. 관리자에게 알려 주세요",
    rejected: "요청한 내용으로는 그림을 만들 수 없습니다",
    timeout: "그림을 만드는 데 시간이 너무 걸려 멈췄습니다",
    failed: "그림을 만들지 못했습니다",
  },
  en: {
    create: "Create image",
    edit: "Edit image",
    zoom: "View larger",
    download: "Download",
    close: "Close",
    loadFailed: "Couldn't load the image",
    notConfigured: "Image creation isn't set up for this connection",
    promptEmpty: "Describe the image you want",
    promptTooLong: (n: number) => `Image descriptions can be up to ${n} characters`,
    tooManySources: (n: number) => `You can attach up to ${n} base images`,
    sourceInvalid: "Only image files can be attached in image mode",
    notFound: "Couldn't find the image to edit",
    providerLimit: "The image service limit was reached. Please tell your administrator",
    rejected: "An image can't be created for this request",
    timeout: "Image creation took too long and was stopped",
    failed: "Couldn't create the image",
  },
});

type Texts = (typeof S)["ko"];

const CREATE_MODE = "image-create";
const EDIT_MODE = "image-edit";
const SLOT = "aimbase-image";
/** 위젯 본체 소독이 data- 속성을 지우므로 그림 id 는 class 이름에 싣는다. */
const SLOT_ID_PREFIX = `${SLOT}--`;
const REF = /^!\[([^\]\n]*)\]\(aimbase-image:([0-9a-fA-F-]{36})\)/;

const IMAGE_ICON =
  '<rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/>';
const ZOOM_ICON =
  '<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" x2="14" y1="3" y2="10"/><line x1="3" x2="10" y1="21" y2="14"/>';
const DOWNLOAD_ICON =
  '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>';
const EDIT_ICON =
  '<path d="M21.17 6.81a1 1 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z"/>';
const CLOSE_ICON = '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>';

const EXTENSIONS: Record<string, string> = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp" };

/** 위젯 설정 조회 응답의 그림 칸(T3-2 위젯 설정 칸 추가). */
interface ImageSettings {
  enabled?: boolean;
  max_prompt_chars?: number;
  max_source_images?: number;
}

interface LoadedImage {
  url: string;
  type: string;
}

function imageSettings(ctx: WidgetContext): ImageSettings | null {
  const s = ctx.widgetSettings() as unknown as { image_generation?: ImageSettings } | null;
  return s?.image_generation ?? null;
}

function escapeAttr(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function slotImageId(slot: HTMLElement): string | null {
  for (const c of Array.from(slot.classList)) {
    if (c.startsWith(SLOT_ID_PREFIX)) return c.slice(SLOT_ID_PREFIX.length);
  }
  return null;
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

/** image-<YYYYMMDD-HHmmss>.<확장자>. 시각은 그 답을 받은 화면 시각이다. */
function fileName(at: Date, type: string): string {
  const stamp = `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`;
  return `image-${stamp}.${EXTENSIONS[type] ?? "png"}`;
}

function button(label: string, iconPaths: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "aimg-btn";
  b.innerHTML = svgIcon(iconPaths, 13);
  b.append(document.createTextNode(label));
  b.addEventListener("click", (e) => {
    e.stopPropagation();
    onClick();
  });
  return b;
}

function saveAs(url: string, name: string): void {
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.rel = "noopener";
  link.click();
}

class ImageGen {
  private readonly t: Texts;
  private readonly cache = new Map<string, Promise<LoadedImage>>();
  private readonly urls = new Set<string>();
  private sourceId: string | null = null;
  private closeZoom: (() => void) | null = null;

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(S);
  }

  install(): void {
    const { ctx, t } = this;
    ctx.ui.plusMenu.addItem({
      id: CREATE_MODE,
      order: 10,
      group: 1,
      icon: svgIcon(IMAGE_ICON, 16),
      label: () => t.create,
      visible: () => imageSettings(ctx)?.enabled === true,
      onSelect: () => this.startCreate(),
    });
    ctx.ui.answer.addMarkdownExtension({ id: "image-gen", extension: this.markdownExtension() });
    ctx.ui.answer.addDecorator({ id: "image-gen", order: 10, decorate: (body, a) => this.decorate(body, a) });
    ctx.ui.addErrorTexts(this.errorTexts());
    ctx.on("answerDone", (a) => this.followNewImage(a));
    ctx.on("newConversation", () => this.closeZoom?.());
    ctx.on("destroy", () => {
      this.closeZoom?.();
      this.urls.forEach((u) => URL.revokeObjectURL(u));
      this.urls.clear();
      this.cache.clear();
    });
  }

  private createMode(): ComposerModeSpec {
    return {
      id: CREATE_MODE,
      label: () => this.t.create,
      icon: svgIcon(IMAGE_ICON, 14),
      requestFields: () => ({ image: { action: "create" } }),
    };
  }

  private editMode(): ComposerModeSpec {
    return {
      id: EDIT_MODE,
      label: () => this.t.edit,
      icon: svgIcon(EDIT_ICON, 14),
      requestFields: () => ({ image: { action: "edit", source_image_id: this.sourceId } }),
      onClear: () => {
        this.sourceId = null;
      },
    };
  }

  private startCreate(): void {
    this.ctx.ui.composer.setMode(this.createMode());
    this.focusInput();
  }

  private startEdit(imageId: string): void {
    // 모드를 바꾸면 앞 모드의 onClear 가 먼저 불리므로 바탕 그림은 바꾼 뒤에 둔다.
    this.ctx.ui.composer.setMode(this.editMode());
    this.sourceId = imageId;
    this.focusInput();
  }

  private focusInput(): void {
    this.ctx.ui.layer.querySelector<HTMLTextAreaElement>("textarea")?.focus();
  }

  /** 그림 고치기 모드에서 새 그림이 오면 바탕을 그 그림으로 옮겨 이어 고친다(T3-4). */
  private followNewImage(a: AnswerView): void {
    if (this.ctx.ui.composer.currentMode() !== EDIT_MODE || a.error || a.stopped) return;
    const req = a.requestBody.image as { action?: string } | undefined;
    if (req?.action !== "edit") return;
    const images = a.extra.images as Array<{ id?: string }> | undefined;
    const id = images?.[0]?.id;
    if (typeof id === "string" && id) this.sourceId = id;
  }

  private errorTexts(): Record<string, () => string> {
    const t = this.t;
    const limits = () => imageSettings(this.ctx);
    return {
      image_not_configured: () => t.notConfigured,
      image_prompt_empty: () => t.promptEmpty,
      image_prompt_too_long: () => {
        const n = limits()?.max_prompt_chars;
        return n ? t.promptTooLong(n) : t.failed;
      },
      image_too_many_sources: () => {
        const n = limits()?.max_source_images;
        return n ? t.tooManySources(n) : t.failed;
      },
      image_source_invalid: () => t.sourceInvalid,
      image_not_found: () => t.notFound,
      image_provider_auth: () => t.providerLimit,
      image_provider_quota: () => t.providerLimit,
      image_rejected: () => t.rejected,
      image_timeout: () => t.timeout,
      image_failed: () => t.failed,
      image_invalid: () => t.failed,
    };
  }

  /** 그림 참조를 강조, 링크 해석보다 먼저 잡아 그림 자리 span 으로 바꾼다. 그리기는 decorate 가 한다. */
  private markdownExtension(): TokenizerAndRendererExtension {
    return {
      name: "aimbaseImage",
      level: "inline",
      start(src: string) {
        const i = src.indexOf("![");
        return i < 0 ? undefined : i;
      },
      tokenizer(src: string) {
        const m = REF.exec(src);
        if (!m) return undefined;
        return { type: "aimbaseImage", raw: m[0], alt: m[1], id: m[2].toLowerCase() };
      },
      renderer(token: Tokens.Generic) {
        return `<span class="${SLOT} ${SLOT_ID_PREFIX}${token.id}" title="${escapeAttr(String(token.alt ?? ""))}"></span>`;
      },
    };
  }

  private decorate(body: HTMLElement, a: AnswerView): void {
    body.querySelectorAll<HTMLElement>(`span.${SLOT}`).forEach((slot) => {
      if (slot.classList.contains("is-filled")) return;
      const id = slotImageId(slot);
      if (!id) return;
      slot.classList.add("is-filled", "is-loading");
      const alt = slot.title;
      slot.removeAttribute("title");
      this.load(id, a.sessionId).then(
        (img) => this.fill(slot, id, alt, img, a),
        () => {
          slot.classList.remove("is-loading");
          slot.classList.add("is-error");
          slot.textContent = this.t.loadFailed;
        },
      );
    });
  }

  private load(id: string, sessionId: string): Promise<LoadedImage> {
    const key = `${sessionId}/${id}`;
    let p = this.cache.get(key);
    if (!p) {
      const path = `/api/v1/chat/images/${encodeURIComponent(id)}?session_id=${encodeURIComponent(sessionId)}`;
      p = this.ctx.api.requestBlob("GET", path).then((blob) => {
        const url = URL.createObjectURL(blob);
        this.urls.add(url);
        return { url, type: blob.type };
      });
      // 못 받은 그림은 다음에 다시 그릴 때 다시 받는다.
      p.catch(() => this.cache.delete(key));
      this.cache.set(key, p);
    }
    return p;
  }

  private fill(slot: HTMLElement, id: string, alt: string, img: LoadedImage, a: AnswerView): void {
    const t = this.t;
    slot.classList.remove("is-loading");
    const el = document.createElement("img");
    el.src = img.url;
    el.alt = alt;
    el.addEventListener("click", () => this.zoom(img, alt, a));
    const bar = document.createElement("span");
    bar.className = "aimg-bar";
    bar.append(
      button(t.zoom, ZOOM_ICON, () => this.zoom(img, alt, a)),
      button(t.download, DOWNLOAD_ICON, () => saveAs(img.url, fileName(a.at, img.type))),
      button(t.edit, EDIT_ICON, () => this.startEdit(id)),
    );
    slot.replaceChildren(el, bar);
  }

  /** 위젯 판 위 덮개. Esc, 바깥 누름, 닫기로 닫는다. */
  private zoom(img: LoadedImage, alt: string, a: AnswerView): void {
    this.closeZoom?.();
    const t = this.t;
    const overlay = document.createElement("div");
    overlay.className = "aimg-ov";
    const big = document.createElement("img");
    big.src = img.url;
    big.alt = alt;
    big.addEventListener("click", (e) => e.stopPropagation());
    const bar = document.createElement("div");
    bar.className = "aimg-ovbar";
    const close = () => {
      offEscape();
      overlay.remove();
      this.closeZoom = null;
    };
    bar.append(
      button(t.download, DOWNLOAD_ICON, () => saveAs(img.url, fileName(a.at, img.type))),
      button(t.close, CLOSE_ICON, close),
    );
    overlay.append(big, bar);
    overlay.addEventListener("click", close);
    const offEscape = this.ctx.ui.onEscape(() => {
      close();
      return true;
    });
    this.closeZoom = close;
    this.ctx.ui.layer.appendChild(overlay);
  }
}

const CSS = `
.${SLOT} { display: block; margin: 8px 0; max-width: 100%; }
.${SLOT}:empty, .${SLOT}.is-loading { width: min(100%, 320px); aspect-ratio: 1 / 1; border-radius: 10px; background: var(--aimbase-chip-bg); }
.${SLOT}.is-error { width: auto; aspect-ratio: auto; padding: 10px 12px; border-radius: 10px; background: var(--aimbase-chip-bg); color: var(--aimbase-muted); font-size: .9em; }
.${SLOT} img { display: block; max-width: 100%; height: auto; border-radius: 10px; cursor: zoom-in; }
.aimg-bar { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.aimg-btn { display: inline-flex; align-items: center; gap: 4px; padding: 3px 9px; border: 1px solid var(--aimbase-border); border-radius: 6px; background: var(--aimbase-bg); color: var(--aimbase-fg); font: inherit; font-size: .85em; cursor: pointer; }
.aimg-btn:hover { background: var(--aimbase-chip-bg); }
.aimg-ov { position: absolute; inset: 0; z-index: 40; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 16px; background: rgba(0, 0, 0, .72); cursor: zoom-out; }
.aimg-ov img { max-width: 100%; max-height: calc(100% - 48px); object-fit: contain; border-radius: 6px; cursor: default; }
.aimg-ovbar { display: flex; gap: 8px; }
`;

export const imageGen: WidgetFeature = {
  id: "image-gen",
  css: CSS,
  install(ctx) {
    new ImageGen(ctx).install();
  },
};
