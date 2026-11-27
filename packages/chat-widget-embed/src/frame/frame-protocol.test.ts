import { describe, expect, it } from "vitest";
import {
  CHAT_ATTRIBUTE_NAMES,
  FRAME_MARK,
  initMessage,
  receive,
  seal,
  tokenMatchesOrigin,
  tokenOrigin,
  unseal,
} from "./frame-protocol";

const PARENT = "https://lexflow.example";
const FRAME = "https://aimbase.example";

function base64Url(value: unknown): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  const binary = Array.from(bytes, (b) => String.fromCharCode(b)).join("");
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function jwt(payload: Record<string, unknown>): string {
  return `${base64Url({ alg: "HS256", typ: "JWT" })}.${base64Url(payload)}.signature`;
}

describe("TC-CHT-170 봉투 검사", () => {
  const frameWindow = { tag: "frame" };
  const peer = { origin: FRAME, source: frameWindow };

  it("표식과 아는 type 이 있는 봉투는 메시지로 돌려준다", () => {
    const event = { data: seal({ type: "ready" }), origin: FRAME, source: frameWindow };
    expect(receive(event, peer)).toEqual({ type: "ready" });
  });

  it("표식 없는 메시지는 무시한다", () => {
    expect(unseal({ type: "ready" })).toBeNull();
    expect(unseal("ready")).toBeNull();
    expect(unseal(null)).toBeNull();
  });

  it("표식은 있고 type 이 모르는 값이면 무시한다", () => {
    expect(unseal({ [FRAME_MARK]: 1, type: "eval" })).toBeNull();
    expect(unseal({ [FRAME_MARK]: 2, type: "ready" })).toBeNull();
  });

  it("다른 출처의 메시지는 무시한다", () => {
    const event = { data: seal({ type: "ready" }), origin: "https://evil.example", source: frameWindow };
    expect(receive(event, peer)).toBeNull();
  });

  it("다른 창(source)의 메시지는 무시한다", () => {
    const event = { data: seal({ type: "ready" }), origin: FRAME, source: { tag: "other" } };
    expect(receive(event, peer)).toBeNull();
  });

  it("출처를 비우면 어느 출처든 받는다 (전용 페이지가 init 을 기다릴 때)", () => {
    const parentWindow = { tag: "parent" };
    const event = { data: seal({ type: "open" }), origin: PARENT, source: parentWindow };
    expect(receive(event, { source: parentWindow })).toEqual({ type: "open" });
  });
});

describe("TC-CHT-171 토큰 origin 과 부모 출처", () => {
  it("claim 이 부모 출처와 같으면 통과한다", () => {
    const token = jwt({ type: "widget", origin: PARENT, user_name: "김변호사" });
    expect(tokenOrigin(token)).toBe(PARENT);
    expect(tokenMatchesOrigin(token, PARENT)).toBe(true);
  });

  it("claim 이 다르면 거절한다", () => {
    expect(tokenMatchesOrigin(jwt({ origin: "https://other.example" }), PARENT)).toBe(false);
  });

  it("토큰이 JWT 꼴이 아니면 거절한다", () => {
    expect(tokenMatchesOrigin("not-a-jwt", PARENT)).toBe(false);
    expect(tokenMatchesOrigin("a.b", PARENT)).toBe(false);
    expect(tokenMatchesOrigin("a.!!!.c", PARENT)).toBe(false);
  });

  it("claim 이 없으면 거절한다", () => {
    expect(tokenMatchesOrigin(jwt({ type: "widget" }), PARENT)).toBe(false);
    expect(tokenMatchesOrigin(jwt({ origin: 42 }), PARENT)).toBe(false);
  });
});

describe("TC-CHT-172 속성 전달", () => {
  it("채팅창 속성은 그대로 가고 frame-src, id, class, style 은 가지 않는다", () => {
    const message = initMessage([
      { name: "frame-src", value: `${FRAME}/widget/v1/frame.html` },
      { name: "id", value: "chat" },
      { name: "class", value: "fixed" },
      { name: "style", value: "z-index: 1" },
      { name: "base-url", value: FRAME },
      { name: "token-endpoint", value: "/api/aimbase/token" },
      { name: "display", value: "panel" },
      { name: "screen-key", value: "case:12" },
      { name: "locale", value: "ko" },
    ]);
    expect(message.type).toBe("init");
    expect(message.display).toBe("panel");
    expect(message.attrs).toEqual({
      "base-url": FRAME,
      "token-endpoint": "/api/aimbase/token",
      display: "panel",
      "screen-key": "case:12",
      locale: "ko",
    });
  });

  it("display 가 없으면 bubble 이다", () => {
    const message = initMessage([{ name: "base-url", value: FRAME }]);
    expect(message.display).toBe("bubble");
    expect(message.attrs.display).toBe("bubble");
  });

  it("<aimbase-chat> 이 받는 속성 전부가 목록에 있다", () => {
    expect(CHAT_ATTRIBUTE_NAMES).toHaveLength(18);
    expect(CHAT_ATTRIBUTE_NAMES).toContain("history-list");
  });
});
