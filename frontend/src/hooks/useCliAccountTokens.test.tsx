import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cliAccountTokensApi } from "../api/cliAccountTokens";
import { useCliAccountTokens, useConnectionAccountToken, usePutConnectionAccountToken } from "./useCliAccountTokens";
import type { AccountTokenRow } from "../types/cliAccountToken";

// TC-TOK-027 토큰 넣기 훅

vi.mock("../api/cliAccountTokens", () => ({
  cliAccountTokensApi: { list: vi.fn(), getForConnection: vi.fn(), putForConnection: vi.fn() },
}));

const api = vi.mocked(cliAccountTokensApi);
const reply = <T,>(data: T) => ({ data: { success: true, data } }) as never;

const before: AccountTokenRow = {
  agent_name: "wms-runner",
  config_dir: "/data/claude/work01",
  managed_by: "tenant",
  present: false,
  reachable: true,
  connection_ids: ["c-sum"],
};
const after: AccountTokenRow = { ...before, present: true, days_left: 365, expires_at: "2027-11-16T18:50:00+09:00" };

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return renderHook(
    () => ({ list: useCliAccountTokens(), one: useConnectionAccountToken("c-sum"), put: usePutConnectionAccountToken() }),
    { wrapper },
  );
}

describe("usePutConnectionAccountToken", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.list.mockResolvedValue(reply([before]));
    api.getForConnection.mockResolvedValue(reply(before));
  });

  it("넣으면 목록을 다시 읽고 그 연결의 토큰 칸은 넣기 응답으로 바꾼다", async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.list.isSuccess && result.current.one.isSuccess).toBe(true));
    api.list.mockResolvedValue(reply([after]));
    api.putForConnection.mockResolvedValue(reply(after));

    await act(() => result.current.put.mutateAsync({ connectionId: "c-sum", token: "sk-ant-oat01-test" }));

    expect(api.putForConnection).toHaveBeenCalledWith("c-sum", "sk-ant-oat01-test");
    await waitFor(() => expect(result.current.list.data).toEqual([after]));
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(result.current.one.data).toEqual(after);
  });

  it("확인에 실패하면 서버 오류 코드로 실패하고 목록과 칸은 그대로 둔다", async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.list.isSuccess && result.current.one.isSuccess).toBe(true));
    api.putForConnection.mockRejectedValue(new Error("account_token_rejected"));

    await act(() =>
      expect(result.current.put.mutateAsync({ connectionId: "c-sum", token: "sk-ant-oat01-test" })).rejects.toThrow(
        "account_token_rejected",
      ),
    );

    expect(api.list).toHaveBeenCalledTimes(1);
    expect(result.current.list.data).toEqual([before]);
    expect(result.current.one.data).toEqual(before);
  });
});
