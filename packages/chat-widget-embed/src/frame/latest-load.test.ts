import { describe, expect, it } from "vitest";
import { LatestLoad } from "./latest-load";

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

describe("TC-CHT-154 위젯 설정 조회 경합", () => {
  it("연결 없음 조회가 CS 연결 조회보다 늦게 와도 CS 연결 설정이 남는다", async () => {
    const loads = new LatestLoad();
    const applied: string[] = [];
    const none = deferred<string>();
    const cs = deferred<string>();
    const load = (reply: Promise<string>) =>
      loads.run(async (isLatest) => {
        const s = await reply;
        if (isLatest()) applied.push(s);
      });

    const first = load(none.promise);
    const second = load(cs.promise);
    cs.resolve("cs-001");
    await second;
    none.resolve("연결 없음");
    await first;

    expect(applied).toEqual(["cs-001"]);
  });

  it("질문은 기다리는 사이 다시 건 조회까지 끝난 뒤에 나간다", async () => {
    const loads = new LatestLoad();
    const a = deferred<void>();
    const b = deferred<void>();
    void loads.run(() => a.promise);
    expect(loads.busy()).toBe(true);

    let sent = false;
    const waiting = loads.settled(10_000).then(() => (sent = true));
    void loads.run(() => b.promise);
    a.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(sent).toBe(false);
    b.resolve();
    await waiting;

    expect(sent).toBe(true);
    expect(loads.busy()).toBe(false);
  });

  it("조회가 멈춰도 시한이 지나면 질문은 나간다", async () => {
    const loads = new LatestLoad();
    void loads.run(() => new Promise<void>(() => undefined));
    const started = Date.now();
    await loads.settled(30);
    expect(Date.now() - started).toBeGreaterThanOrEqual(25);
  });

  it("화면 언어로 받은 설정은 그 사이 연결을 바꾸면 쓰지 않는다", () => {
    const loads = new LatestLoad();
    const sameTarget = loads.mark();
    expect(sameTarget()).toBe(true);
    void loads.run(async () => undefined);
    expect(sameTarget()).toBe(false);
  });
});
