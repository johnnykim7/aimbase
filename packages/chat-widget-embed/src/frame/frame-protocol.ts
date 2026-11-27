import type { DisplayMode, TokenResponse } from "../types";

/**
 * CR-203: 전용 페이지(frame.html)와 연결 스크립트(<aimbase-chat-frame>)가 postMessage 로 주고받는 규약.
 * 양쪽 번들이 함께 쓰므로 위젯 본체를 끌어오지 않는다.
 */

export const FRAME_MARK = "aimbase-chat-frame" as const;
export const FRAME_PROTOCOL_VERSION = 1 as const;
export const FRAME_SRC_ATTRIBUTE = "frame-src";
export const DEFAULT_FRAME_DISPLAY: DisplayMode = "bubble";
export const TOKEN_REPLY_TIMEOUT_MS = 10_000;
export const CONTEXT_REPLY_TIMEOUT_MS = 2_000;

/** <aimbase-chat> 이 받는 속성. 연결 스크립트는 이 이름들만 전용 페이지에 넘긴다. */
export const CHAT_ATTRIBUTE_NAMES = [
  "base-url",
  "token-endpoint",
  "display",
  "theme-mode",
  "rag-source-id",
  "session-id",
  "connection-id",
  "connection-group-id",
  "actions-enabled",
  "model",
  "suggestions",
  "empty-title",
  "empty-subtitle",
  "font-size",
  "locale",
  "allow-approval",
  "screen-key",
  "history-list",
] as const;

export type ChatAttributeName = (typeof CHAT_ATTRIBUTE_NAMES)[number];

export function isChatAttribute(name: string): name is ChatAttributeName {
  return (CHAT_ATTRIBUTE_NAMES as readonly string[]).includes(name);
}

export interface FrameState {
  open: boolean;
  expanded: boolean;
  docked: boolean;
}

export type FrameMessage =
  | { type: "ready" }
  | { type: "init"; attrs: Record<string, string>; display: DisplayMode }
  | { type: "token:request"; id: string }
  | { type: "token:reply"; id: string; token?: TokenResponse; error?: string }
  | { type: "context:request"; id: string }
  | { type: "context:reply"; id: string; context?: Record<string, unknown> }
  | { type: "attr"; name: string; value: string | null }
  | { type: "open" }
  | { type: "close" }
  | ({ type: "state" } & FrameState)
  | { type: "drag"; screenX: number; screenY: number }
  | { type: "event"; name: string; detail?: unknown };

export type FrameMessageType = FrameMessage["type"];
export type InitMessage = Extract<FrameMessage, { type: "init" }>;

const MESSAGE_TYPES: ReadonlySet<string> = new Set<FrameMessageType>([
  "ready",
  "init",
  "token:request",
  "token:reply",
  "context:request",
  "context:reply",
  "attr",
  "open",
  "close",
  "state",
  "drag",
  "event",
]);

export type FrameEnvelope = FrameMessage & { [FRAME_MARK]: typeof FRAME_PROTOCOL_VERSION };

export function seal(message: FrameMessage): FrameEnvelope {
  return { [FRAME_MARK]: FRAME_PROTOCOL_VERSION, ...message } as FrameEnvelope;
}

/** 표식과 아는 type 이 있는 봉투만 메시지로 돌려준다. 나머지는 null. */
export function unseal(data: unknown): FrameMessage | null {
  if (data === null || typeof data !== "object") return null;
  const record = data as Record<string, unknown>;
  if (record[FRAME_MARK] !== FRAME_PROTOCOL_VERSION) return null;
  if (typeof record.type !== "string" || !MESSAGE_TYPES.has(record.type)) return null;
  const { [FRAME_MARK]: _mark, ...message } = record;
  return message as FrameMessage;
}

export interface IncomingMessage {
  data: unknown;
  origin: string;
  source: unknown;
}

/** 받을 상대. origin 을 비우면 어느 출처든 받는다(전용 페이지가 init 을 기다릴 때만). */
export interface Peer {
  origin?: string;
  source: unknown;
}

/** 기대한 출처와 창에서 온 봉투만 메시지로 돌려준다 (BIZ-301). */
export function receive(event: IncomingMessage, peer: Peer): FrameMessage | null {
  if (peer.origin !== undefined && event.origin !== peer.origin) return null;
  if (event.source !== peer.source) return null;
  return unseal(event.data);
}

export interface NamedValue {
  name: string;
  value: string;
}

/** 연결 요소의 속성에서 init 메시지를 만든다. 채팅창 속성만 넘기고 display 가 없으면 bubble. */
export function initMessage(attributes: Iterable<NamedValue>): InitMessage {
  const attrs: Record<string, string> = {};
  for (const { name, value } of attributes) {
    if (isChatAttribute(name)) attrs[name] = value;
  }
  if (!attrs.display) attrs.display = DEFAULT_FRAME_DISPLAY;
  return { type: "init", attrs, display: attrs.display as DisplayMode };
}

function base64UrlToBytes(text: string): Uint8Array {
  const base64 = text.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** JWT 의 내용(payload)만 읽는다. 서명은 서버가 검사하므로 여기서는 보지 않는다. */
export function decodeTokenClaims(token: string): Record<string, unknown> | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const parsed: unknown = JSON.parse(new TextDecoder().decode(base64UrlToBytes(parts[1])));
    return parsed !== null && typeof parsed === "object" ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

export function tokenOrigin(token: string): string | null {
  const origin = decodeTokenClaims(token)?.origin;
  return typeof origin === "string" ? origin : null;
}

/** 토큰의 origin claim 이 부모 출처와 같은가 (BIZ-301). claim 이 없거나 토큰이 깨지면 거절. */
export function tokenMatchesOrigin(token: string, parentOrigin: string): boolean {
  const origin = tokenOrigin(token);
  return origin !== null && origin === parentOrigin;
}
