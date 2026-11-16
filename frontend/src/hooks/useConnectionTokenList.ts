import { useMemo } from "react";
import type { Connection } from "../types/connection";
import type { AccountTokenRow } from "../types/cliAccountToken";
import type { ChipFilter } from "../components/connections/TokenStateChips";
import { displayAdapter, isServerRunner } from "../components/connections/adapterName";
import { stateRank, tenantTokenState, type ChipState, type TokenState } from "../components/connections/accountTokenState";

export interface ConnectionRow {
  conn: Connection;
  token: AccountTokenRow | undefined;
  state: TokenState;
  kind: string;
}

/**
 * CR-196: 연결 관리 목록. 연결마다 그 연결 id 가 든 계정 폴더의 토큰을 잇고, 검색어로 거른 줄의 칩 건수를 센 뒤
 * 고른 칩으로 거르고 토큰 상태 순서(기한 지남, 없음, 30일 이내 먼저)로 늘어놓는다. 칩 건수는 칩을 고르기 전 줄로 센다.
 */
export function useConnectionTokenList(
  connections: Connection[],
  tokens: AccountTokenRow[] | undefined,
  query: string,
  chip: ChipFilter,
) {
  const rows: ConnectionRow[] = useMemo(() => {
    const byConnection = new Map<string, AccountTokenRow>();
    for (const t of tokens ?? []) for (const id of t.connection_ids) byConnection.set(id, t);
    return connections.map((conn) => {
      const token = byConnection.get(conn.id);
      return {
        conn,
        token,
        state: tenantTokenState(token, isServerRunner(conn)),
        kind: displayAdapter(conn.type ?? "llm", conn.adapter ?? ""),
      };
    });
  }, [connections, tokens]);

  const searched = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) =>
      [r.conn.name, r.conn.id, r.kind, r.token?.agent_name, r.token?.config_dir]
        .filter(Boolean)
        .join(" ")
        .toLowerCase()
        .includes(q),
    );
  }, [rows, query]);

  const counts = useMemo(() => {
    const c: Partial<Record<ChipState, number>> = {};
    for (const r of searched) if (r.state !== "unknown") c[r.state] = (c[r.state] ?? 0) + 1;
    return c;
  }, [searched]);

  const shown = useMemo(
    () =>
      searched
        .filter((r) => chip === "all" || r.state === chip)
        .sort((a, b) => stateRank(a.state) - stateRank(b.state) || (a.conn.name || a.conn.id).localeCompare(b.conn.name || b.conn.id)),
    [searched, chip],
  );

  return { searched, counts, shown };
}
