import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { StartupGate } from "./startup-gate";

function deferred() {
  let resolve!: () => void;
  let reject!: (e: Error) => void;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("StartupGate", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("설정을 받기 전에 보내면 받을 때까지 기다린다", async () => {
    const gate = new StartupGate();
    const settings = deferred();
    gate.hold(settings.promise);
    void gate.finish();

    let sent = false;
    void gate.wait(10_000).then(() => (sent = true));
    await vi.advanceTimersByTimeAsync(500);
    expect(sent).toBe(false);

    settings.resolve();
    await vi.advanceTimersByTimeAsync(0);
    expect(sent).toBe(true);
    expect(gate.isOpen()).toBe(true);
  });

  it("설정을 받는 동안 기능이 건 선호 조회도 기다린다", async () => {
    const gate = new StartupGate();
    const settings = deferred();
    const preferences = deferred();
    gate.hold(
      settings.promise.then(() => {
        gate.hold(preferences.promise);
      }),
    );
    void gate.finish();

    let sent = false;
    void gate.wait(10_000).then(() => (sent = true));
    settings.resolve();
    await vi.advanceTimersByTimeAsync(0);
    expect(sent).toBe(false);

    preferences.resolve();
    await vi.advanceTimersByTimeAsync(0);
    expect(sent).toBe(true);
  });

  it("조회가 실패해도 열린다", async () => {
    const gate = new StartupGate();
    const settings = deferred();
    gate.hold(settings.promise);
    void gate.finish();

    let sent = false;
    void gate.wait(10_000).then(() => (sent = true));
    settings.reject(new Error("503"));
    await vi.advanceTimersByTimeAsync(0);
    expect(sent).toBe(true);
  });

  it("조회가 멈춰 있으면 시한이 지나 보낸다", async () => {
    const gate = new StartupGate();
    gate.hold(new Promise(() => undefined));
    void gate.finish();

    let sent = false;
    void gate.wait(10_000).then(() => (sent = true));
    await vi.advanceTimersByTimeAsync(9_999);
    expect(sent).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(sent).toBe(true);
    expect(gate.isOpen()).toBe(false);
  });

  it("열린 뒤에는 기다리지 않고 새로 건 일도 받지 않는다", async () => {
    const gate = new StartupGate();
    await gate.finish();
    gate.hold(new Promise(() => undefined));

    let sent = false;
    void gate.wait(10_000).then(() => (sent = true));
    await vi.advanceTimersByTimeAsync(0);
    expect(sent).toBe(true);
  });
});
