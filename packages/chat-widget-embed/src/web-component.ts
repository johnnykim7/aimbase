import { createWidget } from "./widget";
import type { WidgetHandle, WidgetOptions } from "./types";

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
    return ["base-url", "token-endpoint", "display", "theme-mode", "rag-source-id", "session-id"];
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

    const options: WidgetOptions = {
      baseUrl,
      authResolver: resolver,
      display: displayAttr,
      sessionId: this.getAttribute("session-id") ?? undefined,
      ragSourceId: this.ragSourceId ?? this.getAttribute("rag-source-id") ?? undefined,
      target: displayAttr === "inline" ? (this as HTMLElement) : undefined,
      theme: themeAttr ? { mode: themeAttr } : undefined,
      contextProvider: this.contextProvider ?? undefined,
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
