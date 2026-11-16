import { describe, expect, it } from "vitest";
import { renderHook } from "@testing-library/react";
import { useConnectionTokenList } from "./useConnectionTokenList";
import type { Connection } from "../types/connection";
import type { AccountTokenRow } from "../types/cliAccountToken";
import type { ChipFilter } from "../components/connections/TokenStateChips";

// TC-TOK-026 연결 관리 목록 훅

const cli = (id: string, name: string, agent?: string): Connection => ({
  id,
  name,
  type: "llm",
  adapter: "anthropic-cli",
  config: agent ? { agent_name: agent } : {},
});

const token = (over: Partial<AccountTokenRow> & Pick<AccountTokenRow, "agent_name" | "config_dir" | "connection_ids">): AccountTokenRow => ({
  managed_by: "tenant",
  present: true,
  days_left: 200,
  reachable: true,
  ...over,
});

const connections: Connection[] = [
  cli("c-sum", "wms summary", "wms-runner"),
  cli("c-detail", "wms detail", "wms-runner"),
  cli("c-guide", "outbound guide", "wes-runner"),
  cli("c-check", "return check", "wes-runner"),
  cli("c-notice", "notice", "gw-runner"),
  cli("c-lent", "lent account", "chaws-runner"),
  cli("c-unread", "unread", "off-runner"),
  cli("c-pc", "pc runner"),
  { id: "c-db", name: "order db", type: "database", adapter: "PostgreSQL", config: {} },
];

const shared = token({ agent_name: "wms-runner", config_dir: "/data/claude/work01", connection_ids: ["c-sum", "c-detail"] });
const tokens: AccountTokenRow[] = [
  shared,
  token({ agent_name: "wes-runner", config_dir: "/data/claude/work02", connection_ids: ["c-guide"], present: false, days_left: undefined }),
  token({ agent_name: "wes-runner", config_dir: "/data/claude/work03", connection_ids: ["c-check"], days_left: -2 }),
  token({ agent_name: "gw-runner", config_dir: "/data/claude/work01", connection_ids: ["c-notice"], days_left: 10 }),
  token({ agent_name: "chaws-runner", config_dir: "/data/claude/lent", connection_ids: ["c-lent"], managed_by: "platform", days_left: -5 }),
];

const run = (query = "", chip: ChipFilter = "all") =>
  renderHook(() => useConnectionTokenList(connections, tokens, query, chip)).result.current;

const ids = (rows: { conn: Connection }[]) => rows.map((r) => r.conn.id);

describe("useConnectionTokenList", () => {
  it("연결 id 로 그 계정 폴더의 토큰을 잇고 줄마다 상태를 가른다", () => {
    const { shown } = run();
    const byId = new Map(shown.map((r) => [r.conn.id, r]));

    expect(byId.get("c-sum")?.token).toBe(shared);
    expect(byId.get("c-detail")?.token).toBe(shared);
    expect(Object.fromEntries(shown.map((r) => [r.conn.id, r.state]))).toEqual({
      "c-sum": "ok",
      "c-detail": "ok",
      "c-guide": "missing",
      "c-check": "expired",
      "c-notice": "soon",
      "c-lent": "platform",
      "c-unread": "unknown",
      "c-pc": "not_cli",
      "c-db": "not_cli",
    });
    expect(byId.get("c-pc")?.token).toBeUndefined();
  });

  it("이름, 어댑터, 실행기, 계정 폴더로 찾고 대소문자를 가리지 않는다", () => {
    expect(ids(run("WMS").shown).sort()).toEqual(["c-detail", "c-sum"]);
    expect(ids(run("postgresql").shown)).toEqual(["c-db"]);
    expect(ids(run("claude cli").shown)).toHaveLength(8);
    expect(ids(run("wes-runner").shown).sort()).toEqual(["c-check", "c-guide"]);
    expect(ids(run("work03").shown)).toEqual(["c-check"]);
    expect(run("  ").shown).toHaveLength(connections.length);
  });

  it("칩 건수는 찾은 줄로 세고 불러오지 못한 줄은 세지 않는다", () => {
    expect(run().counts).toEqual({ ok: 2, missing: 1, expired: 1, soon: 1, platform: 1, not_cli: 2 });
    expect(run("wes-runner").counts).toEqual({ missing: 1, expired: 1 });
    expect(run("", "ok").counts).toEqual(run().counts);
    expect(run("", "ok").searched).toHaveLength(connections.length);
  });

  it("기한 지남, 없음, 30일 이내 순으로 먼저 놓고 같은 상태는 이름 순이다", () => {
    expect(ids(run().shown)).toEqual(["c-check", "c-guide", "c-notice", "c-detail", "c-sum", "c-lent", "c-unread", "c-db", "c-pc"]);
    expect(ids(run("", "ok").shown)).toEqual(["c-detail", "c-sum"]);
    expect(ids(run("", "not_cli").shown)).toEqual(["c-db", "c-pc"]);
  });
});
