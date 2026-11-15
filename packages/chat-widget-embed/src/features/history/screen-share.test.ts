import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ConversationView, MenuItemSpec, QuestionView, WidgetContext } from "../../frame/context";
import { WidgetApiError } from "../../widget-api";
import { history } from "./index";

type Msgs = Record<string, unknown>;

function messages(sessionId: string, turns = 1, shared = false): Msgs {
  return {
    session_id: sessionId,
    title: `t-${sessionId}`,
    chat_project_id: null,
    truncated: false,
    shared,
    turns: Array.from({ length: turns }, (_, i) => ({
      turn_id: `turn-${i}`,
      user_seq: i * 2 + 1,
      question: { text: `q${i}`, created_at: null, attachments: [], asker: { user_ref: "u1", user_name: "cs_kim" } },
      answer: { text: `a${i}`, model: null, created_at: null, stopped: false, tool_uses: [] },
      feedback: null,
    })),
  };
}

interface Opts {
  screenKey?: string | null;
  historyList?: boolean;
  settings: Record<string, unknown>;
  rows?: Record<string, Array<{ session_id: string }>>;
  convs?: Record<string, Msgs>;
  docks?: boolean;
  userRef?: string | null;
}

function setup(opts: Opts) {
  const handlers = new Map<string, Array<(payload: unknown) => void>>();
  const moreItems: MenuItemSpec[] = [];
  const requestFields: Array<() => Record<string, unknown>> = [];
  const decorators: Array<(q: QuestionView) => HTMLElement | null> = [];
  const errorTexts: Record<string, () => string> = {};
  const opened: ConversationView[] = [];
  const notices: string[] = [];
  const waits: Promise<unknown>[] = [];
  let sessionId = "s-fresh";
  let rewindGuard: ((q: QuestionView) => boolean) | null = null;
  const leftHandle = {
    open: vi.fn(),
    close: vi.fn(),
    isOpen: vi.fn(() => false),
    setAvailable: vi.fn(),
    docks: vi.fn(() => opts.docks ?? false),
  };
  const mount = vi.fn(() => leftHandle);
  const header = { setTitleTag: vi.fn(), setNotice: vi.fn(), setNewConversationVisible: vi.fn() };
  const setAsker = vi.fn();
  let historyList = opts.historyList ?? true;
  const request = vi.fn(async (method: string, path: string) => {
    if (method === "POST" && path === "/api/v1/chat/sessions/shared") return { session_id: "shared-1", created: false };
    const m = path.match(/^\/api\/v1\/chat\/sessions\/([^/?]+)\/messages$/);
    if (method === "GET" && m) {
      const found = opts.convs?.[decodeURIComponent(m[1])];
      if (!found) throw new WidgetApiError(404, "session_not_found");
      return found;
    }
    if (method === "GET" && path.startsWith("/api/v1/chat/sessions?")) {
      const q = new URLSearchParams(path.split("?")[1]);
      return opts.rows?.[q.get("screen_key") ?? ""] ?? [];
    }
    return [];
  });
  const ctx = {
    strings: (pack: { ko: unknown }) => pack.ko,
    sessionId: () => sessionId,
    isBusy: () => false,
    widgetSettings: () => opts.settings,
    screenKey: () => opts.screenKey ?? null,
    historyList: () => historyList,
    on: (event: string, fn: (payload: unknown) => void) => {
      handlers.set(event, [...(handlers.get(event) ?? []), fn]);
      return () => {};
    },
    ui: {
      moreMenu: { addItem: (spec: MenuItemSpec) => moreItems.push(spec) },
      plusMenu: { addItem: () => {} },
      leftPane: { mount },
      header,
      question: { addDecorator: (fn: (q: QuestionView) => HTMLElement | null) => decorators.push(fn) },
      addErrorTexts: (map: Record<string, () => string>) => Object.assign(errorTexts, map),
      notice: (text: string) => notices.push(text),
      toast: () => {},
    },
    chat: {
      open: vi.fn(async (view: ConversationView) => {
        sessionId = view.sessionId;
        opened.push(view);
      }),
      abort: vi.fn(async () => {}),
      newConversation: vi.fn(async () => {}),
      addRequestFields: (fn: () => Record<string, unknown>) => requestFields.push(fn),
      waitBeforeSend: (task: Promise<unknown>) => waits.push(task),
      setAsker,
      setRewindGuard: (fn: ((q: QuestionView) => boolean) | null) => (rewindGuard = fn),
      setTitle: () => {},
    },
    api: { request, userRef: () => (opts.userRef === undefined ? "u1" : opts.userRef) },
  } as unknown as WidgetContext;
  history.install(ctx);
  const emit = (event: string, payload: unknown = {}) => handlers.get(event)?.forEach((fn) => fn(payload));
  return {
    emit,
    request,
    mount,
    leftHandle,
    header,
    setAsker,
    opened,
    notices,
    waits,
    errorTexts,
    moreItems,
    decorators,
    guard: () => rewindGuard,
    fields: () => Object.assign({}, ...requestFields.map((fn) => fn())),
    setHistoryList: (v: boolean) => (historyList = v),
    sessionId: () => sessionId,
    calls: (method: string, prefix: string) =>
      request.mock.calls.filter(([m, p]) => m === method && (p as string).startsWith(prefix)).map(([, p]) => p as string),
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));
const answerDone = (sessionId: string, extra: Record<string, unknown> = {}) => ({
  sessionId,
  question: "q",
  text: "a",
  citations: [],
  artifacts: [],
  stopped: false,
  latest: true,
  at: new Date(),
  requestBody: {},
  extra: {},
  ...extra,
});

class FakeStorage {
  private readonly map = new Map<string, string>();
  getItem(k: string) {
    return this.map.get(k) ?? null;
  }
  setItem(k: string, v: string) {
    this.map.set(k, v);
  }
}

const MEMORY = "aimbase-chat:screen-session:bo2:orders";

beforeEach(() => {
  vi.stubGlobal("sessionStorage", new FakeStorage());
  vi.stubGlobal("document", {
    createElement: () => ({ className: "", textContent: "" }),
  });
});
afterEach(() => vi.unstubAllGlobals());

describe("TC-CHT-151 위젯 속성(메뉴 화면)", () => {
  it("screen-key 를 주면 목록을 그 화면 키로 거르고 탭 기억이 없으면 그 화면의 가장 최근 대화를 연다", async () => {
    const w = setup({
      screenKey: "bo2:orders",
      settings: { history_enabled: true },
      rows: { "bo2:orders": [{ session_id: "s-latest" }] },
      convs: { "s-latest": messages("s-latest") },
    });
    w.emit("settingsLoaded");
    await settle();
    expect(w.calls("GET", "/api/v1/chat/sessions?")).toEqual([
      "/api/v1/chat/sessions?page=0&size=1&screen_key=bo2%3Aorders",
    ]);
    expect(w.opened.map((v) => v.sessionId)).toEqual(["s-latest"]);
    expect(sessionStorage.getItem(MEMORY)).toBe("s-latest");
    expect(w.mount).toHaveBeenCalledWith(expect.objectContaining({ dock: true }));
  });

  it("탭 기억이 있으면 그 대화를, 그 대화를 열 수 없으면 가장 최근 대화를 연다(BIZ-279)", async () => {
    sessionStorage.setItem(MEMORY, "s-mine");
    const w = setup({
      screenKey: "bo2:orders",
      settings: { history_enabled: true },
      rows: { "bo2:orders": [{ session_id: "s-latest" }] },
      convs: { "s-mine": messages("s-mine"), "s-latest": messages("s-latest") },
    });
    w.emit("settingsLoaded");
    await settle();
    expect(w.opened.map((v) => v.sessionId)).toEqual(["s-mine"]);
    expect(w.calls("GET", "/api/v1/chat/sessions?")).toEqual([]);

    sessionStorage.setItem(MEMORY, "s-gone");
    const w2 = setup({
      screenKey: "bo2:orders",
      settings: { history_enabled: true },
      rows: { "bo2:orders": [{ session_id: "s-latest" }] },
      convs: { "s-latest": messages("s-latest") },
    });
    w2.emit("settingsLoaded");
    await settle();
    expect(w2.opened.map((v) => v.sessionId)).toEqual(["s-latest"]);
  });

  it("그 화면의 대화가 없으면 첫 화면 그대로 두고, 새 대화의 첫 질문에만 screen_key 를 싣는다", async () => {
    const w = setup({ screenKey: "bo2:orders", settings: { history_enabled: true } });
    w.emit("settingsLoaded");
    await settle();
    expect(w.opened).toEqual([]);
    expect(w.fields()).toEqual({ screen_key: "bo2:orders" });
    w.emit("answerDone", answerDone(w.sessionId()));
    expect(w.fields()).toEqual({});
    expect(sessionStorage.getItem(MEMORY)).toBe("s-fresh");
  });

  it("history-list off 면 목록 칸을 달지 않고 대화 열기는 그대로 한다. 켜면 그 자리에서 목록이 붙는다", async () => {
    const w = setup({
      screenKey: "bo2:orders",
      historyList: false,
      settings: { history_enabled: true },
      rows: { "bo2:orders": [{ session_id: "s-latest" }] },
      convs: { "s-latest": messages("s-latest") },
    });
    w.emit("settingsLoaded");
    await settle();
    expect(w.mount).not.toHaveBeenCalled();
    expect(w.opened.map((v) => v.sessionId)).toEqual(["s-latest"]);

    w.setHistoryList(true);
    w.emit("optionsChange");
    expect(w.mount).toHaveBeenCalledTimes(1);
    expect(w.leftHandle.setAvailable).toHaveBeenLastCalledWith(true);

    w.setHistoryList(false);
    w.emit("optionsChange");
    expect(w.leftHandle.setAvailable).toHaveBeenLastCalledWith(false);
  });

  it("창을 열 때 붙일 수 있으면 목록을 편다. 붙일 수 없는 폭이면 열지 않는다", async () => {
    const w = setup({ screenKey: "bo2:orders", settings: { history_enabled: true }, docks: true });
    w.emit("settingsLoaded");
    w.emit("panelToggle", { open: true });
    expect(w.leftHandle.open).toHaveBeenCalledTimes(1);

    const narrow = setup({ screenKey: "bo2:orders", settings: { history_enabled: true }, docks: false });
    narrow.emit("settingsLoaded");
    narrow.emit("panelToggle", { open: true });
    expect(narrow.leftHandle.open).not.toHaveBeenCalled();
  });

  it("아무 속성도 없으면 CR-178 그대로(그 대화 번호로 다시 보이기, screen_key 를 싣지 않음, 목록은 덮어 연다)", async () => {
    const w = setup({ settings: { history_enabled: true }, convs: { "s-fresh": messages("s-fresh") } });
    w.emit("settingsLoaded");
    await settle();
    expect(w.calls("GET", "/api/v1/chat/sessions?")).toEqual([]);
    expect(w.calls("GET", "/api/v1/chat/sessions/s-fresh/messages")).toHaveLength(1);
    expect(w.fields()).toEqual({});
    expect(w.mount).toHaveBeenCalledWith(expect.objectContaining({ dock: false }));
    expect(w.header.setNotice).not.toHaveBeenCalled();
    expect(w.setAsker).not.toHaveBeenCalled();
  });
});

describe("TC-CHT-151 위젯 속성(건 화면, 함께 보는 대화)", () => {
  const sharedSettings = { history_enabled: true, shared_available: true, user_name: "cs_kim" };

  it("토큰에 함께 보기 키가 있으면 그 건의 대화를 열고 목록, 새 대화, 대화 옮기기를 숨기고 안내 줄을 단다", async () => {
    const w = setup({ screenKey: "bo2:cs", settings: sharedSettings, convs: { "shared-1": messages("shared-1", 2, true) } });
    w.emit("settingsLoaded");
    await settle();
    expect(w.calls("POST", "/api/v1/chat/sessions/shared")).toHaveLength(1);
    expect(w.opened.map((v) => v.sessionId)).toEqual(["shared-1"]);
    expect(w.opened[0].turns[0].question.asker).toEqual({ userRef: "u1", userName: "cs_kim" });
    expect(w.waits).toHaveLength(1);
    expect(w.mount).not.toHaveBeenCalled();
    expect(w.header.setNewConversationVisible).toHaveBeenCalledWith(false);
    const notice = w.header.setNotice.mock.calls[0][0] as () => string;
    expect(notice()).toBe("이 건을 여는 사람 모두가 함께 보는 대화입니다");
    expect(w.setAsker).toHaveBeenCalledWith({ userRef: "u1", userName: "cs_kim" });
    expect(w.moreItems.find((i) => i.id === "history-move")?.visible?.()).toBe(false);
    // 함께 보는 대화에는 화면 키를 싣지 않는다(서버가 함께 보기 키를 화면 키로 둔다)
    expect(w.fields()).toEqual({});
    // 설정을 다시 받아도(언어 변경 등) 한 번만 연다
    w.emit("settingsLoaded");
    await settle();
    expect(w.calls("POST", "/api/v1/chat/sessions/shared")).toHaveLength(1);
  });

  it("다시 생성과 질문 수정은 마지막 질문이 내 질문일 때만 둔다(BIZ-280)", () => {
    const w = setup({ settings: sharedSettings, convs: { "shared-1": messages("shared-1") } });
    w.emit("settingsLoaded");
    const guard = w.guard()!;
    const me = { userRef: "u1" };
    const other = { userRef: "u2" };
    expect(guard({ sessionId: "shared-1", last: true, asker: me })).toBe(true);
    expect(guard({ sessionId: "shared-1", last: true, asker: other })).toBe(false);
    expect(guard({ sessionId: "shared-1", last: false, asker: me })).toBe(false);
    expect(guard({ sessionId: "shared-1", last: true })).toBe(false);
  });

  it("질문 위에 묻는 사람을, 내 질문은 「이름 (나)」로 단다. 이름이 없으면 user_ref, 함께 보는 대화가 아니면 달지 않는다", () => {
    const w = setup({ settings: sharedSettings });
    const label = (q: QuestionView) => w.decorators[0](q) as unknown as { className: string; textContent: string } | null;
    expect(label({ sessionId: "x", last: true, asker: { userRef: "u2", userName: "kim01" } })).toBeNull();
    w.emit("settingsLoaded");
    expect(label({ sessionId: "x", last: true, asker: { userRef: "u1", userName: "cs_kim" } })).toEqual({
      className: "asker me",
      textContent: "cs_kim (나)",
    });
    expect(label({ sessionId: "x", last: true, asker: { userRef: "u2", userName: "kim01" } })).toEqual({
      className: "asker",
      textContent: "kim01",
    });
    expect(label({ sessionId: "x", last: true, asker: { userRef: "u3" } })?.textContent).toBe("u3");
    expect(label({ sessionId: "x", last: true })).toBeNull();
  });

  it("다시 생성이 409 rewind_rejected:shared 면 대화를 다시 읽어 그리고 알린다", async () => {
    const w = setup({ settings: sharedSettings, convs: { "shared-1": messages("shared-1", 3, true) } });
    w.emit("settingsLoaded");
    await settle();
    expect(w.errorTexts["rewind_rejected:shared"]()).toBe("그 사이에 다른 사람이 질문해 대화를 다시 불러왔습니다");
    w.emit("answerDone", answerDone("shared-1", { error: "x", errorCode: "rewind_rejected:shared" }));
    await settle();
    expect(w.opened.map((v) => v.sessionId)).toEqual(["shared-1", "shared-1"]);
    expect(w.notices).toEqual(["그 사이에 다른 사람이 질문해 대화를 다시 불러왔습니다"]);
  });
});
