import { afterEach, describe, expect, it, vi } from "vitest";
import type { TokenStore } from "./token-store";
import type { ApprovalEvent } from "./types";
import { WorkflowClient } from "./workflow-client";

class FakeEventSource {
  static CLOSED = 2;
  static last: FakeEventSource | null = null;
  readyState = 1;
  onerror: (() => void) | null = null;
  private readonly listeners = new Map<string, Array<(e: { data: string }) => void>>();
  constructor(readonly url: string) {
    FakeEventSource.last = this;
  }
  addEventListener(name: string, fn: (e: { data: string }) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), fn]);
  }
  close() {
    this.readyState = FakeEventSource.CLOSED;
  }
  emit(name: string, data: unknown) {
    for (const fn of this.listeners.get(name) ?? []) fn({ data: JSON.stringify(data) });
  }
}

const approval = (id: string | undefined, step = "h1") => ({
  run_id: "run-1", step_id: step, policy_id: step, reason: "출고 전 확인", approvers: [], ...(id ? { approval_id: id } : {}),
});

async function subscribe(onApproval: (ev: ApprovalEvent) => void) {
  vi.stubGlobal("EventSource", FakeEventSource);
  const tokens = { getToken: async () => "tok" } as unknown as TokenStore;
  await new WorkflowClient(tokens).subscribe("http://x", "run-1", { onApproval });
  return FakeEventSource.last!;
}

afterEach(() => vi.unstubAllGlobals());

describe("TC-CHT-119 워크플로우 구독의 승인 알림", () => {
  it("같은 승인 번호는 구독 때 다시 받은 것과 실시간 알림이 겹쳐도 한 번만 전한다", async () => {
    const got: ApprovalEvent[] = [];
    const es = await subscribe((ev) => got.push(ev));
    es.emit("workflow.approval", approval("a-1"));
    es.emit("workflow.approval", approval("a-1"));
    es.emit("workflow.approval", approval("a-2", "h2"));
    expect(got.map((ev) => ev.approval_id)).toEqual(["a-1", "a-2"]);
  });

  it("승인 번호가 없는 옛 서버 알림은 그대로 전한다", async () => {
    const got: ApprovalEvent[] = [];
    const es = await subscribe((ev) => got.push(ev));
    es.emit("workflow.approval", approval(undefined));
    es.emit("workflow.approval", approval(undefined));
    expect(got).toHaveLength(2);
  });
});
