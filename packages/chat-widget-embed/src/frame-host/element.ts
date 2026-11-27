/**
 * CR-203: <aimbase-chat-frame> 연결 요소. 붙이는 앱 화면에 넣으면 Aimbase 채팅창 전용 페이지를 iframe 으로 끼운다.
 * 속성은 <aimbase-chat> 과 같고 frame-src 만 더한다. 채팅창 코드는 Aimbase 출처에서 돌고, 이 요소는 토큰과
 * 화면 자료를 건네고 상자를 그리는 일만 한다. 토큰은 지금처럼 붙이는 앱 BFF(token-endpoint)에서 쿠키로 받는다.
 */
import type { AuthResolver, ContextProvider } from "../types";
import {
  CHAT_ATTRIBUTE_NAMES,
  FRAME_SRC_ATTRIBUTE,
  RELAYED_EVENTS,
  frameSrcOf,
  initMessage,
  plainContext,
  receive,
  seal,
  type FrameMessage,
  type FrameState,
} from "../frame/frame-protocol";
import { fetchWidgetToken, plainToken } from "../frame/token-endpoint";
import { HOST_CSS, HostWindow } from "./host-window";

/** 전용 페이지가 쓰는 브라우저 기능. 음성 입력, 이 화면 캡처, 복사, 크게 보기 */
export const FRAME_ALLOW = "microphone; display-capture; clipboard-read; clipboard-write; fullscreen";

/** 붙을 때만 읽는 속성. 바뀌어도 전용 페이지에 넘기지 않는다 */
const MOUNT_ONLY = new Set(["display", "base-url", "token-endpoint", FRAME_SRC_ATTRIBUTE]);

export class AimbaseChatFrameElement extends HTMLElement {
  /** 고급 사용. token-endpoint 대신 토큰을 직접 준다 */
  public authResolver: AuthResolver | null = null;
  /** 질문을 보낼 때 실을 화면 자료. JSON 값만 간다 */
  public contextProvider: ContextProvider | null = null;
  public ragSourceId: string | null = null;

  private iframe: HTMLIFrameElement | null = null;
  private hostWindow: HostWindow | null = null;
  private frameOrigin = "";
  private ready = false;
  private queued: FrameMessage[] = [];
  private state: FrameState | null = null;
  private readonly onMessage = (e: MessageEvent) => this.receive(e);

  static get observedAttributes(): string[] {
    return [...CHAT_ATTRIBUTE_NAMES];
  }

  connectedCallback(): void {
    if (this.iframe) return;
    const src = frameSrcOf(this.getAttribute(FRAME_SRC_ATTRIBUTE), this.getAttribute("base-url"), location.href);
    if (!src) {
      console.error("[aimbase-chat-frame] base-url or frame-src attribute is required");
      return;
    }
    if (!this.authResolver && !this.getAttribute("token-endpoint")) {
      console.error("[aimbase-chat-frame] token-endpoint attribute or authResolver property is required");
      return;
    }
    const display = initMessage(this.attributeList()).display;
    this.frameOrigin = src.origin;
    this.setAttribute("data-display", display);

    const shadow = this.shadowRoot ?? this.attachShadow({ mode: "open" });
    const style = document.createElement("style");
    style.textContent = HOST_CSS;
    shadow.replaceChildren(style);

    const iframe = document.createElement("iframe");
    iframe.src = src.href;
    iframe.allow = FRAME_ALLOW;
    this.iframe = iframe;
    this.hostWindow = new HostWindow(shadow, display, iframe, () => this.post({ type: "expand", expanded: false }));
    window.addEventListener("message", this.onMessage);
  }

  disconnectedCallback(): void {
    window.removeEventListener("message", this.onMessage);
    this.hostWindow?.destroy();
    this.hostWindow = null;
    this.iframe = null;
    this.ready = false;
    this.queued = [];
    this.state = null;
  }

  attributeChangedCallback(name: string, oldValue: string | null, newValue: string | null): void {
    if (!this.iframe || oldValue === newValue || MOUNT_ONLY.has(name)) return;
    this.post({ type: "attr", name, value: newValue });
  }

  public open(): void {
    this.post({ type: "open" });
  }

  public close(): void {
    this.post({ type: "close" });
  }

  public isOpen(): boolean {
    return this.state?.open ?? false;
  }

  private attributeList(): Array<{ name: string; value: string }> {
    const list = Array.from(this.attributes, (a) => ({ name: a.name, value: a.value }));
    if (this.ragSourceId) list.push({ name: "rag-source-id", value: this.ragSourceId });
    return list;
  }

  /** init 전에 부른 열기, 닫기, 속성 바꾸기는 init 뒤에 보낸다 */
  private post(m: FrameMessage): void {
    if (!this.ready) {
      if (m.type !== "init") this.queued.push(m);
      return;
    }
    this.send(m);
  }

  private send(m: FrameMessage): void {
    this.iframe?.contentWindow?.postMessage(seal(m), this.frameOrigin);
  }

  private receive(event: MessageEvent): void {
    const iframe = this.iframe;
    if (!iframe) return;
    const m = receive(event, { origin: this.frameOrigin, source: iframe.contentWindow });
    if (!m) return;
    switch (m.type) {
      case "ready": {
        // 전용 페이지가 다시 뜨면(새로 고침) 처음부터 다시 잇는다.
        this.ready = true;
        this.send(initMessage(this.attributeList()));
        const queued = this.queued;
        this.queued = [];
        for (const q of queued) this.send(q);
        break;
      }
      case "token:request":
        void this.replyToken(m.id);
        break;
      case "context:request":
        void this.replyContext(m.id);
        break;
      case "state":
        this.state = { open: m.open, expanded: m.expanded, docked: m.docked };
        this.hostWindow?.setState(this.state);
        break;
      case "drag":
        this.hostWindow?.drag(m.phase, m.screenX, m.screenY);
        break;
      case "event":
        if ((RELAYED_EVENTS as readonly string[]).includes(m.name)) {
          this.dispatchEvent(new CustomEvent(m.name, { detail: m.detail, bubbles: true, composed: true }));
        }
        break;
      default:
        break;
    }
  }

  private async replyToken(id: string): Promise<void> {
    try {
      const endpoint = this.getAttribute("token-endpoint");
      const resolver = this.authResolver ?? (endpoint ? () => fetchWidgetToken(endpoint) : null);
      if (!resolver) throw new Error("token-endpoint attribute or authResolver property is required");
      this.send({ type: "token:reply", id, token: plainToken(await resolver()) });
    } catch (e) {
      this.send({ type: "token:reply", id, error: e instanceof Error ? e.message : String(e) });
    }
  }

  private async replyContext(id: string): Promise<void> {
    let context: Record<string, unknown> | undefined;
    try {
      context = plainContext(await this.contextProvider?.());
    } catch (e) {
      console.warn("[aimbase-chat-frame] contextProvider failed", e);
    }
    this.send({ type: "context:reply", id, context });
  }
}

export function defineAimbaseChatFrame(): void {
  if (typeof customElements === "undefined") return;
  if (!customElements.get("aimbase-chat-frame")) {
    customElements.define("aimbase-chat-frame", AimbaseChatFrameElement);
  }
}
