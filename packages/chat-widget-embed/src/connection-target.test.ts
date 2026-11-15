import { afterEach, describe, expect, it, vi } from "vitest";
import { ChatClient } from "./chat-client";
import type { TokenStore } from "./token-store";
import { WidgetApi } from "./widget-api";

const tokens = { getToken: async () => "t" } as unknown as TokenStore;

function stubFetch(body: string = JSON.stringify({ data: {} })) {
  const fetchMock = vi.fn(async () => new Response(body, { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const sent = (fetchMock: ReturnType<typeof stubFetch>, i = 0) => {
  const [url, init] = fetchMock.mock.calls[i] as unknown as [string, RequestInit];
  return { url, body: init.body ? JSON.parse(init.body as string) : undefined };
};

afterEach(() => vi.unstubAllGlobals());

describe("TC-CHT-153 위젯 계정 묶음 속성", () => {
  it("묶음을 주면 위젯 설정 조회에 connection_group_id 만 싣는다", async () => {
    const fetchMock = stubFetch();
    const api = new WidgetApi(tokens, "http://a");

    await api.settings({ connectionId: "solo", connectionGroupId: "g1" }, "ko");
    await api.settings({ connectionId: "solo" }, "ko");

    const grouped = new URL(sent(fetchMock, 0).url).searchParams;
    expect(grouped.get("connection_group_id")).toBe("g1");
    expect(grouped.has("connection_id")).toBe(false);
    const single = new URL(sent(fetchMock, 1).url).searchParams;
    expect(single.get("connection_id")).toBe("solo");
    expect(single.has("connection_group_id")).toBe(false);
  });

  it("묶음을 주면 다음 질문 제안 본문에 connection_group_id 만 싣고, 없으면 지금처럼 connection_id", async () => {
    const fetchMock = stubFetch();
    const api = new WidgetApi(tokens, "http://a");

    await api.followUps("s-1", "turn-1", { connectionId: "solo", connectionGroupId: "g1" });
    await api.followUps("s-1", "turn-1", {});

    expect(sent(fetchMock, 0).body).toEqual({ turn_id: "turn-1", connection_group_id: "g1" });
    expect(sent(fetchMock, 1).body).toEqual({ turn_id: "turn-1", connection_id: null });
  });

  it("묶음을 주면 대화 요청에 connection_group_id 만 싣고 기능이 덮어쓸 수 없다", async () => {
    const fetchMock = stubFetch("");
    const chat = new ChatClient(tokens);

    await chat.sendMessage({
      baseUrl: "http://a", sessionId: "s-1", text: "안녕", connectionId: "solo", connectionGroupId: "g1",
      extra: { connection_group_id: "other" },
    }, () => {});
    await chat.sendMessage({ baseUrl: "http://a", sessionId: "s-1", text: "안녕", connectionId: "solo" }, () => {});

    expect(sent(fetchMock, 0).body.connection_group_id).toBe("g1");
    expect(sent(fetchMock, 0).body).not.toHaveProperty("connection_id");
    expect(sent(fetchMock, 1).body.connection_id).toBe("solo");
    expect(sent(fetchMock, 1).body).not.toHaveProperty("connection_group_id");
  });
});
