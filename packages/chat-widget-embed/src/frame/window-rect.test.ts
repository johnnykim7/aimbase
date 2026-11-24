import { describe, expect, it } from "vitest";
import {
  FLOAT_RECT_STORAGE_KEY,
  SIDE_WIDTH_STORAGE_KEY,
  defaultFloatingRect,
  fitFloating,
  fitSideWidth,
  moveFloating,
  narrowFromDock,
  readFloatingRect,
  readSideWidth,
  resizeFloating,
  resizeSide,
  storeFloatingRect,
  storeSideWidth,
  widenForDock,
  type StorageLike,
} from "./window-rect";

const DESK = { width: 1280, height: 800 };
const PHONE = { width: 360, height: 640 };

function memoryStore(init: Record<string, string> = {}): StorageLike & { data: Record<string, string> } {
  const data = { ...init };
  return {
    data,
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = v;
    },
  };
}

const blockedStore: StorageLike = {
  getItem: () => {
    throw new Error("SecurityError");
  },
  setItem: () => {
    throw new Error("QuotaExceededError");
  },
};

describe("TC-CHT-156 화면 안으로 맞추기", () => {
  it("오른쪽 아래로 나간 창은 크기 그대로 8px 안쪽으로 옮긴다", () => {
    expect(fitFloating({ left: 1200, top: 700, width: 400, height: 600 }, DESK))
      .toEqual({ left: 872, top: 192, width: 400, height: 600 });
  });

  it("화면보다 큰 창은 화면에서 16px 뺀 크기로 줄이고 8px 안쪽에 둔다", () => {
    expect(fitFloating({ left: -50, top: -50, width: 2000, height: 1200 }, DESK))
      .toEqual({ left: 8, top: 8, width: 1264, height: 784 });
  });

  it("좁은 화면에서는 처음 자리를 화면 폭에 맞춘다", () => {
    const r = fitFloating(defaultFloatingRect(PHONE), PHONE);
    expect(r.width).toBe(344);
    expect(r.left).toBe(8);
    expect(r.top).toBeGreaterThanOrEqual(8);
    expect(r.top + r.height).toBeLessThanOrEqual(PHONE.height - 8);
  });

  it("기억이 없으면 오른쪽 20px, 아래 90px 에 400x600 으로 연다", () => {
    expect(fitFloating(defaultFloatingRect(DESK), DESK))
      .toEqual({ left: 860, top: 110, width: 400, height: 600 });
  });

  it("처음 높이는 화면 높이 80% 를 넘지 않는다", () => {
    expect(defaultFloatingRect({ width: 1280, height: 700 }).height).toBe(560);
  });
});

describe("TC-CHT-157 끌기와 크기 바꾸기", () => {
  const start = { left: 400, top: 100, width: 400, height: 600 };

  it("머리줄을 화면 밖까지 끌면 크기 그대로 가장자리 8px 에서 멈춘다", () => {
    expect(moveFloating(start, 5000, 5000, DESK)).toEqual({ left: 872, top: 192, width: 400, height: 600 });
    expect(moveFloating(start, -5000, -5000, DESK)).toEqual({ left: 8, top: 8, width: 400, height: 600 });
  });

  it("왼쪽 변을 끌면 오른쪽 변은 그대로 폭만 바뀐다", () => {
    const r = resizeFloating(start, "w", -200, 0, DESK);
    expect(r).toEqual({ left: 200, top: 100, width: 600, height: 600 });
    expect(r.left + r.width).toBe(start.left + start.width);
  });

  it("오른쪽 아래 모서리를 최소보다 작게 끌면 320x400 에서 멈춘다", () => {
    expect(resizeFloating(start, "se", -1000, -1000, DESK)).toEqual({ left: 400, top: 100, width: 320, height: 400 });
  });

  it("왼쪽 위 모서리로 줄이면 오른쪽 변과 아래 변은 그대로다", () => {
    const r = resizeFloating(start, "nw", 1000, 1000, DESK);
    expect(r).toEqual({ left: 480, top: 300, width: 320, height: 400 });
  });

  it("위 변을 화면 위로 끌면 8px 에서 멈추고 아래 변은 그대로다", () => {
    expect(resizeFloating(start, "n", 0, -500, DESK)).toEqual({ left: 400, top: 8, width: 400, height: 692 });
  });

  it("오른쪽 아래 모서리를 화면 밖으로 끌면 가장자리 8px 까지만 늘어난다", () => {
    expect(resizeFloating(start, "se", 5000, 5000, DESK)).toEqual({ left: 400, top: 100, width: 872, height: 692 });
  });

  it("왼쪽 칸을 붙이면 오른쪽 변은 그대로 두고 왼쪽으로 240px 넓히며, 떼면 대화 창으로 돌아온다", () => {
    const desk = fitFloating(defaultFloatingRect(DESK), DESK);
    const docked = fitFloating(widenForDock(desk, 240), DESK, 560);
    expect(docked).toEqual({ left: 620, top: 110, width: 640, height: 600 });
    expect(narrowFromDock(docked, 240)).toEqual(desk);
  });

  it("칸을 붙인 창은 대화 최소 폭에 칸 폭을 더한 560px 아래로 줄지 않는다", () => {
    const docked = { left: 160, top: 100, width: 640, height: 600 };
    expect(resizeFloating(docked, "se", -1000, 0, DESK, 560).width).toBe(560);
    expect(resizeFloating(docked, "w", 1000, 0, DESK, 560)).toEqual({ left: 240, top: 100, width: 560, height: 600 });
  });

  it("옆 칸 폭은 320px 아래로 줄지 않고 화면 폭에서 8px 뺀 값을 넘지 않는다", () => {
    expect(resizeSide(420, -200, DESK)).toBe(620);
    expect(resizeSide(420, 1000, DESK)).toBe(320);
    expect(resizeSide(420, -5000, DESK)).toBe(1272);
    expect(fitSideWidth(420, { width: 300, height: 600 })).toBe(292);
  });
});

describe("TC-CHT-158 기억 읽고 쓰기", () => {
  it("저장한 자리와 크기, 폭을 그대로 읽는다", () => {
    const store = memoryStore();
    storeFloatingRect(store, { left: 10.4, top: 20.6, width: 500, height: 450 });
    storeSideWidth(store, 600.2);
    expect(readFloatingRect(store)).toEqual({ left: 10, top: 21, width: 500, height: 450 });
    expect(readSideWidth(store)).toBe(600);
  });

  it("깨진 값은 없는 것으로 본다", () => {
    for (const raw of ["{", "null", "\"text\"", "[1,2,3,4]", "{\"left\":1,\"top\":2,\"width\":\"a\",\"height\":3}",
      "{\"left\":1,\"top\":2,\"width\":0,\"height\":3}", "{\"left\":1,\"top\":2,\"width\":300,\"height\":-1}"]) {
      expect(readFloatingRect(memoryStore({ [FLOAT_RECT_STORAGE_KEY]: raw }))).toBeNull();
    }
    for (const raw of ["abc", "0", "-5", "{}", "\"400\""]) {
      expect(readSideWidth(memoryStore({ [SIDE_WIDTH_STORAGE_KEY]: raw }))).toBeNull();
    }
    expect(readFloatingRect(memoryStore())).toBeNull();
    expect(readFloatingRect(null)).toBeNull();
  });

  it("저장소가 막혀 있으면 예외 없이 없음이고 쓰기는 조용히 넘어간다", () => {
    expect(readFloatingRect(blockedStore)).toBeNull();
    expect(readSideWidth(blockedStore)).toBeNull();
    expect(() => storeFloatingRect(blockedStore, { left: 1, top: 1, width: 400, height: 400 })).not.toThrow();
    expect(() => storeSideWidth(blockedStore, 400)).not.toThrow();
  });
});
