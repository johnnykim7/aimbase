/**
 * CR-203: 채팅창 전용 페이지(frame.html)의 스크립트. 붙이는 앱 화면이 iframe 으로 끼운다.
 * 위젯은 Aimbase 출처에서 돌고, 토큰과 화면 자료는 부모 화면에 메시지로 묻는다(BIZ-301).
 * 안내만 보일 때는 위젯 본체를 내려받지 않도록 위젯은 init 을 받은 뒤에 불러온다.
 */
import type { AimbaseChatElement } from "../web-component";
import type { FrameBridge, TokenResponse } from "../types";
import {
  CONTEXT_REPLY_TIMEOUT_MS,
  TOKEN_REPLY_TIMEOUT_MS,
  isChatAttribute,
  receive,
  seal,
  tokenMatchesOrigin,
  type FrameMessage,
  type InitMessage,
} from "../frame/frame-protocol";
import { FRAME_TEXT } from "../frame/frame-strings";
import { DEFAULT_LOCALE, normalizeLocale, type Locale } from "../frame/i18n";

/** 부모 화면이 주어도 전용 페이지가 정하는 속성. 토큰은 부모가 받고, 서버는 이 페이지와 같은 출처다(BIZ-302) */
const FRAME_OWNED_ATTRIBUTES = new Set(["token-endpoint", "base-url"]);

type Pending = (m: FrameMessage | null) => void;

function showNotice(doc: Document, text: string): void {
  const p = doc.createElement("p");
  p.className = "aimbase-frame-notice";
  p.setAttribute("role", "status");
  p.textContent = text;
  doc.body.replaceChildren(p);
}

function pageLocale(win: Window, attrs?: Record<string, string>): Locale {
  return normalizeLocale(attrs?.locale) ?? normalizeLocale(win.navigator?.language) ?? DEFAULT_LOCALE;
}

/** 부모가 준 base-url 이 이 페이지와 같은 출처면 그 주소(경로 포함), 아니면 이 페이지의 출처. */
function serverBase(win: Window, given: string | undefined): string {
  if (given) {
    try {
      const url = new URL(given, win.location.href);
      if (url.origin === win.location.origin) return url.href.replace(/\/+$/, "");
    } catch {
      // 주소가 아니면 이 페이지 출처를 쓴다.
    }
  }
  return win.location.origin;
}

export function startFramePage(win: Window = window): void {
  const doc = win.document;
  const parent = win.parent;
  if (!parent || parent === win) {
    showNotice(doc, FRAME_TEXT[pageLocale(win)].standalone);
    return;
  }

  let parentOrigin: string | null = null;
  let locale: Locale = pageLocale(win);
  let chat: AimbaseChatElement | null = null;
  let controls: { setExpanded(v: boolean): void } | null = null;
  let refused = false;
  let seq = 0;
  const pending = new Map<string, Pending>();

  const post = (m: FrameMessage) => {
    if (parentOrigin) parent.postMessage(seal(m), parentOrigin);
  };

  function ask(type: "token:request" | "context:request", timeoutMs: number): Promise<FrameMessage | null> {
    const id = `${type}:${++seq}`;
    return new Promise((resolve) => {
      const timer = win.setTimeout(() => settle(null), timeoutMs);
      const settle: Pending = (m) => {
        win.clearTimeout(timer);
        pending.delete(id);
        resolve(m);
      };
      pending.set(id, settle);
      post({ type, id } as FrameMessage);
    });
  }

  function refuse(): void {
    if (refused) return;
    refused = true;
    chat?.remove();
    chat = null;
    console.error(
      `[aimbase-chat-frame] widget token origin does not match the embedding page (${parentOrigin}). ` +
        "Issue the token with this page's origin.",
    );
    showNotice(doc, FRAME_TEXT[locale].refused);
    // 닫힌 버블 단추 크기로는 안내를 읽을 수 없어 창을 연 크기로 알린다.
    post({ type: "state", open: true, expanded: false, docked: false });
  }

  async function resolveToken(): Promise<TokenResponse> {
    const reply = await ask("token:request", TOKEN_REPLY_TIMEOUT_MS);
    if (!reply || reply.type !== "token:reply") throw new Error("token reply timeout");
    if (reply.error || !reply.token?.token) throw new Error(reply.error ?? "token missing");
    if (!parentOrigin || !tokenMatchesOrigin(reply.token.token, parentOrigin)) {
      refuse();
      throw new Error("token origin mismatch");
    }
    return reply.token;
  }

  async function resolveContext(): Promise<Record<string, unknown> | undefined> {
    const reply = await ask("context:request", CONTEXT_REPLY_TIMEOUT_MS);
    return reply?.type === "context:reply" ? reply.context : undefined;
  }

  const bridge: FrameBridge = {
    stateChanged: (state) => {
      if (!refused) post({ type: "state", ...state });
    },
    drag: (phase, screenX, screenY) => post({ type: "drag", phase, screenX, screenY }),
    attach: (c) => {
      controls = c;
    },
  };

  async function mount(init: InitMessage): Promise<void> {
    locale = pageLocale(win, init.attrs);
    const { defineAimbaseChat } = await import("../web-component");
    if (refused) return;
    defineAimbaseChat();
    const el = doc.createElement("aimbase-chat") as AimbaseChatElement;
    for (const [name, value] of Object.entries(init.attrs)) {
      if (isChatAttribute(name) && !FRAME_OWNED_ATTRIBUTES.has(name)) el.setAttribute(name, value);
    }
    el.setAttribute("base-url", serverBase(win, init.attrs["base-url"]));
    el.authResolver = resolveToken;
    el.contextProvider = resolveContext;
    el.frameBridge = bridge;
    el.addEventListener("new-conversation", (e) => {
      post({ type: "event", name: "new-conversation", detail: (e as CustomEvent).detail });
    });
    chat = el;
    doc.body.appendChild(el);
  }

  function onParentMessage(m: FrameMessage): void {
    switch (m.type) {
      case "token:reply":
      case "context:reply":
        pending.get(m.id)?.(m);
        break;
      case "attr":
        if (!chat || !isChatAttribute(m.name) || FRAME_OWNED_ATTRIBUTES.has(m.name)) break;
        if (m.value === null) chat.removeAttribute(m.name);
        else chat.setAttribute(m.name, m.value);
        break;
      case "open":
        chat?.open();
        break;
      case "close":
        chat?.close();
        break;
      case "expand":
        controls?.setExpanded(m.expanded);
        break;
      default:
        break;
    }
  }

  win.addEventListener("message", (event) => {
    const m = receive(event, { origin: parentOrigin ?? undefined, source: parent });
    if (!m) return;
    if (parentOrigin === null) {
      // 처음 init 을 보낸 부모 출처만 믿는다. 끼울 수 있는 출처는 frame-ancestors 가 이미 막았다(BIZ-300).
      if (m.type !== "init" || event.origin === "null") return;
      parentOrigin = event.origin;
      void mount(m).catch((e) => console.error("[aimbase-chat-frame] failed to load the chat", e));
      return;
    }
    onParentMessage(m);
  });

  // 내용이 없어 어느 출처로 보내도 된다. init 을 받은 뒤로는 그 출처로만 보낸다.
  parent.postMessage(seal({ type: "ready" }), "*");
}

startFramePage();
