import { createWidget } from "./widget";
import { LOCALES, type Locale, type LocalizedList, type LocalizedText } from "./frame/i18n";
import type { FontSize, WidgetHandle, WidgetOptions } from "./types";

const FONT_SIZES: FontSize[] = ["small", "medium", "large"];

/**
 * suggestions 속성은 JSON 글 배열이거나, 화면 언어마다 글 배열을 둔 JSON 객체다.
 * 예: ["주문 현황"] 또는 {"ko":["주문 현황"],"en":["Order status"]}. 깨진 값은 무시하고 첫 화면을 추천 질문 없이 보인다.
 */
function parseSuggestions(raw: string | null): LocalizedList | undefined {
  if (!raw) return undefined;
  const strings = (v: unknown): string[] | undefined =>
    Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : undefined;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed)) return strings(parsed);
    if (parsed && typeof parsed === "object") {
      const out: Partial<Record<Locale, string[]>> = {};
      for (const locale of LOCALES) {
        const list = strings((parsed as Record<string, unknown>)[locale]);
        if (list) out[locale] = list;
      }
      return out;
    }
  } catch {
    // 아래에서 알린다.
  }
  console.error("[aimbase-chat] suggestions attribute must be a JSON array of strings or an object of locale to array");
  return undefined;
}

/** empty-title, empty-subtitle 은 글 그대로이거나, { 로 시작하면 화면 언어마다 글을 둔 JSON 객체다. */
function parseLocalizedText(raw: string | null): LocalizedText | undefined {
  if (raw === null) return undefined;
  if (!raw.trimStart().startsWith("{")) return raw;
  try {
    const parsed: unknown = JSON.parse(raw);
    const out: Partial<Record<Locale, string>> = {};
    for (const locale of LOCALES) {
      const v = (parsed as Record<string, unknown>)?.[locale];
      if (typeof v === "string") out[locale] = v;
    }
    return out;
  } catch {
    return raw;
  }
}

/**
 * <aimbase-chat> 커스텀 엘리먼트.
 *
 * 기본 사용 — 스크립트에 baseUrl / token-endpoint 속성만 주면 동작.
 *   <aimbase-chat base-url="https://aimbase.company.com" token-endpoint="/my-bff/aimbase-token"></aimbase-chat>
 *
 * 고급 사용 — JS 에서 프로퍼티로 authResolver 직접 주입 가능:
 *   document.querySelector('aimbase-chat').authResolver = async () => ({ ... });
 */
export class AimbaseChatElement extends HTMLElement {
  private handle: WidgetHandle | null = null;

  /** 사용자가 JS 로 직접 설정할 수 있는 프로퍼티 */
  public authResolver: WidgetOptions["authResolver"] | null = null;
  public contextProvider: WidgetOptions["contextProvider"] | null = null;
  public ragSourceId: string | null = null;

  static get observedAttributes(): string[] {
    return [
      "base-url",
      "token-endpoint",
      "display",
      "theme-mode",
      "rag-source-id",
      "session-id",
      "connection-id",
      "actions-enabled",
      "model",
      "suggestions",
      "empty-title",
      "empty-subtitle",
      "font-size",
      "locale",
    ];
  }

  connectedCallback(): void {
    const baseUrl = this.getAttribute("base-url");
    if (!baseUrl) {
      console.error("[aimbase-chat] base-url attribute is required");
      return;
    }

    const tokenEndpoint = this.getAttribute("token-endpoint");
    const resolver =
      this.authResolver ??
      (tokenEndpoint
        ? async () => {
            const res = await fetch(tokenEndpoint, { method: "POST", credentials: "include" });
            if (!res.ok) throw new Error(`token endpoint ${res.status}`);
            const data = (await res.json()) as { data?: Record<string, unknown>; token?: string };
            // Aimbase ApiResponse 는 { data: { token, expires_at, refresh_after, scopes } }
            const payload = (data.data ?? data) as {
              token: string;
              expires_at: string | number;
              refresh_after: number;
              scopes?: string[];
            };
            return payload;
          }
        : null);

    if (!resolver) {
      console.error("[aimbase-chat] token-endpoint attribute or authResolver property is required");
      return;
    }

    const displayAttr = (this.getAttribute("display") ?? "inline") as WidgetOptions["display"];
    const themeAttr = this.getAttribute("theme-mode") as "light" | "dark" | "auto" | null;

    const actionsAttr = this.getAttribute("actions-enabled");
    const fontSizeAttr = this.getAttribute("font-size") as FontSize | null;
    const options: WidgetOptions = {
      baseUrl,
      authResolver: resolver,
      display: displayAttr,
      sessionId: this.getAttribute("session-id") ?? undefined,
      ragSourceId: this.ragSourceId ?? this.getAttribute("rag-source-id") ?? undefined,
      connectionId: this.getAttribute("connection-id") ?? undefined,
      actionsEnabled: actionsAttr === "true" || actionsAttr === "" ? true : undefined,
      model: this.getAttribute("model") ?? undefined,
      target: displayAttr === "inline" ? (this as HTMLElement) : undefined,
      theme: themeAttr ? { mode: themeAttr } : undefined,
      contextProvider: this.contextProvider ?? undefined,
      suggestions: parseSuggestions(this.getAttribute("suggestions")),
      emptyTitle: parseLocalizedText(this.getAttribute("empty-title")),
      emptySubtitle: parseLocalizedText(this.getAttribute("empty-subtitle")),
      locale: this.getAttribute("locale") ?? undefined,
      fontSize: fontSizeAttr && FONT_SIZES.includes(fontSizeAttr) ? fontSizeAttr : undefined,
      on: {
        // 붙이는 앱이 새 대화 번호를 따라가도록 엘리먼트 이벤트로 알린다.
        onNewConversation: (detail) =>
          this.dispatchEvent(new CustomEvent("new-conversation", { detail, bubbles: true, composed: true })),
      },
    };

    this.handle = createWidget(options);
  }

  disconnectedCallback(): void {
    this.handle?.destroy();
    this.handle = null;
  }

  /** JS 에서 프로그램적으로 워크플로우 구독 */
  public subscribeWorkflow(runId: string): () => void {
    return this.handle?.subscribeWorkflow(runId) ?? (() => {});
  }

  public open(): void {
    this.handle?.open();
  }
  public close(): void {
    this.handle?.close();
  }
}

/** 엘리먼트 등록 — 중복 등록 방지. */
export function defineAimbaseChat(): void {
  if (typeof customElements === "undefined") return;
  if (!customElements.get("aimbase-chat")) {
    customElements.define("aimbase-chat", AimbaseChatElement);
  }
}
