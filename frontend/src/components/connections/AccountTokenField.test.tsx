import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { AccountTokenField } from "./AccountTokenField";
import { putErrorMessage } from "./accountTokenState";
import type { AccountTokenRow } from "../../types/cliAccountToken";

// TC-TOK-027 토큰 넣기 칸. 훅이 돌려준 실패를 문장으로 보이고, 붙여 넣은 값은 지우지 않는다.

const row: AccountTokenRow = {
  agent_name: "wms-runner",
  config_dir: "/data/claude/work01",
  managed_by: "tenant",
  present: true,
  days_left: 3,
  expires_at: "2026-11-19T09:00:00+09:00",
  reachable: true,
  connection_ids: ["c-sum"],
};

// 한 줄 칸은 브라우저가 줄바꿈을 먼저 걷으므로 칸이 걷어 내는 빈칸은 띄어쓰기로 본다.
const PASTED = "sk-ant-oat01- pasted ";

function fill(onSubmit: (token: string) => Promise<AccountTokenRow>) {
  render(<AccountTokenField row={row} platformView={false} onSubmit={onSubmit} />);
  const input = screen.getByPlaceholderText("sk-ant-oat01-...") as HTMLInputElement;
  fireEvent.change(input, { target: { value: PASTED } });
  fireEvent.click(screen.getByRole("button"));
  return input;
}

describe("AccountTokenField", () => {
  it("확인에 실패하면 그 사유를 보이고 넣은 값은 남긴다", async () => {
    const error = new Error("account_token_rejected");
    const onSubmit = vi.fn().mockRejectedValue(error);

    const input = fill(onSubmit);

    expect(await screen.findByText(putErrorMessage(error))).toBeTruthy();
    expect(onSubmit).toHaveBeenCalledWith("sk-ant-oat01-pasted");
    expect(input.value).toBe(PASTED);
    expect(input.disabled).toBe(false);
  });

  it("넣으면 칸을 비우고 새 기한을 보인다", async () => {
    const onSubmit = vi.fn().mockResolvedValue({ ...row, days_left: 365, expires_at: "2027-11-16T18:50:00+09:00" });

    const input = fill(onSubmit);

    expect(await screen.findByText(/2027-11-16/)).toBeTruthy();
    expect(input.value).toBe("");
  });
});
