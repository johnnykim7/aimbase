import { describe, expect, it, vi } from "vitest";
import type { MenuItemSpec, WidgetContext } from "../frame/context";
import { WidgetApiError } from "../widget-api";
import { memory } from "./memory";

const PREFERENCES_PATH = "/api/v1/chat/me/preferences";

function setup(opts: { userRef: boolean; preferences?: () => Promise<unknown> }) {
  const handlers = new Map<string, Array<(payload: unknown) => void>>();
  const items: MenuItemSpec[] = [];
  const request = vi.fn(async (method: string, path: string) => {
    if (method === "GET" && path === PREFERENCES_PATH) {
      if (opts.preferences) return opts.preferences();
      return { instructions: "", instructions_max_chars: 1500, memory_enabled: true, last_model: null, last_thinking: null };
    }
    return {};
  });
  const ctx = {
    strings: (pack: { ko: unknown }) => pack.ko,
    ui: { moreMenu: { addItem: (spec: MenuItemSpec) => items.push(spec) } },
    on: (event: string, fn: (payload: unknown) => void) => {
      handlers.set(event, [...(handlers.get(event) ?? []), fn]);
      return () => {};
    },
    api: { request, hasUserRef: () => opts.userRef },
    chat: { selectModel: vi.fn(() => true), setThinking: vi.fn(() => true) },
  } as unknown as WidgetContext;
  memory.install(ctx);
  const emit = (event: string) => handlers.get(event)?.forEach((fn) => fn({}));
  const preferenceCalls = () => request.mock.calls.filter(([m, p]) => m === "GET" && p === PREFERENCES_PATH).length;
  const visible = () => items.find((i) => i.id === "memory")?.visible?.() ?? true;
  return { emit, preferenceCalls, visible };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe("TC-CHT-050 주인과 스위치(위젯 기억과 선호 메뉴)", () => {
  it("토큰에 user_ref 가 없으면 내 선호를 조회하지 않고 메뉴를 숨긴다", async () => {
    const w = setup({ userRef: false });
    w.emit("settingsLoaded");
    await settle();
    expect(w.preferenceCalls()).toBe(0);
    expect(w.visible()).toBe(false);
  });

  it("user_ref 가 있으면 설정을 받은 뒤 한 번 조회하고 성공하면 메뉴가 보인다", async () => {
    const w = setup({ userRef: true });
    expect(w.preferenceCalls()).toBe(0);
    w.emit("settingsLoaded");
    await settle();
    expect(w.preferenceCalls()).toBe(1);
    expect(w.visible()).toBe(true);
  });

  it("화면 언어를 바꿔 설정을 다시 받아도 다시 조회하지 않는다", async () => {
    const w = setup({ userRef: true });
    w.emit("settingsLoaded");
    await settle();
    w.emit("settingsLoaded");
    await settle();
    expect(w.preferenceCalls()).toBe(1);
  });

  it("테넌트가 기억을 껐으면(409) 메뉴를 숨긴다", async () => {
    const w = setup({
      userRef: true,
      preferences: () => Promise.reject(new WidgetApiError(409, "memory_disabled")),
    });
    w.emit("settingsLoaded");
    await settle();
    expect(w.preferenceCalls()).toBe(1);
    expect(w.visible()).toBe(false);
  });
});
