import { describe, expect, it } from "vitest";
import { dragFloating, hostBox, type HostLayout } from "./host-window";

const VP = { width: 1280, height: 800 };
const closed = { open: false, expanded: false, docked: false };
const opened = { open: true, expanded: false, docked: false };

function layout(over: Partial<HostLayout>): HostLayout {
  return { display: "bubble", state: opened, viewport: VP, floating: null, sideWidth: null, ...over };
}

describe("TC-CHT-173 display 와 상태별 상자", () => {
  it("bubble 닫힘은 56 x 56, 아래 20, 오른쪽 20", () => {
    expect(hostBox(layout({ state: closed }))).toEqual({
      kind: "bubble",
      rect: { left: 1280 - 20 - 56, top: 800 - 20 - 56, width: 56, height: 56 },
    });
  });

  it("bubble 열림은 기억이 없으면 400 x 600, 아래 90, 오른쪽 20", () => {
    expect(hostBox(layout({}))).toEqual({
      kind: "floating",
      rect: { left: 1280 - 20 - 400, top: 800 - 90 - 600, width: 400, height: 600 },
    });
  });

  it("bubble 열림은 기억한 자리를 화면 안에 맞춘다", () => {
    const box = hostBox(layout({ floating: { left: 1200, top: -50, width: 500, height: 700 } }));
    expect(box).toEqual({ kind: "floating", rect: { left: 1280 - 8 - 500, top: 8, width: 500, height: 700 } });
  });

  it("크게 보기는 CSS 가 가장자리 28 안쪽을 채운다", () => {
    expect(hostBox(layout({ state: { ...opened, expanded: true } }))).toEqual({ kind: "expanded" });
    expect(hostBox(layout({ display: "panel", state: { ...opened, expanded: true } }))).toEqual({ kind: "expanded" });
  });

  it("왼쪽 칸을 붙이면 폭 640, 오른쪽 변은 그대로", () => {
    expect(hostBox(layout({ state: { ...opened, docked: true } }))).toEqual({
      kind: "floating",
      rect: { left: 1280 - 20 - 640, top: 110, width: 640, height: 600 },
    });
  });

  it("panel 은 오른쪽 세로 전체 폭 420, 기억한 폭이 있으면 그 폭", () => {
    expect(hostBox(layout({ display: "panel" }))).toEqual({ kind: "side", width: 420 });
    expect(hostBox(layout({ display: "panel", sideWidth: 600 }))).toEqual({ kind: "side", width: 600 });
  });

  it("inline 은 요소 크기, 닫히면 숨김", () => {
    expect(hostBox(layout({ display: "inline" }))).toEqual({ kind: "inline" });
    expect(hostBox(layout({ display: "inline", state: closed }))).toEqual({ kind: "hidden" });
    expect(hostBox(layout({ display: "panel", state: closed }))).toEqual({ kind: "hidden" });
  });
});

describe("TC-CHT-174 화면 좌표 끌기", () => {
  const start = { left: 860, top: 110, width: 400, height: 600 };

  it("(1000, 500) 에서 (1010, 520) 으로 끌면 오른쪽 10, 아래 20 옮겨진다", () => {
    expect(dragFloating(start, { x: 1000, y: 500 }, { x: 1010, y: 520 }, VP, false)).toEqual({
      ...start,
      left: 870,
      top: 130,
    });
  });

  it("화면 밖까지 끌어도 가장자리 8px 에서 멈춘다", () => {
    expect(dragFloating(start, { x: 1000, y: 500 }, { x: 3000, y: -2000 }, VP, false)).toEqual({
      ...start,
      left: 1280 - 8 - 400,
      top: 8,
    });
  });
});
