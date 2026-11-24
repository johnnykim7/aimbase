/**
 * 떠 있는 위젯과 옆 칸 위젯의 자리, 크기 계산과 기억(CR-198).
 * 화면 요소를 만지지 않아 단위시험으로 본다. 화면에 붙이는 일은 window-drag.ts 가 한다.
 */

export interface Rect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface Viewport {
  width: number;
  height: number;
}

/** 끄는 변이나 모서리. 북(n), 남(s), 동(e), 서(w) */
export type Edge = "n" | "s" | "e" | "w" | "ne" | "nw" | "se" | "sw";

export const EDGES: readonly Edge[] = ["n", "s", "e", "w", "ne", "nw", "se", "sw"];

/** 창과 화면 가장자리 사이에 늘 남기는 틈(BIZ-286). */
export const EDGE_GAP_PX = 8;
/** BIZ-287 */
export const FLOAT_MIN_WIDTH_PX = 320;
export const FLOAT_MIN_HEIGHT_PX = 400;
export const SIDE_MIN_WIDTH_PX = 320;

/** 기억이 없을 때의 처음 자리. 이 건 전 CSS 고정 자리와 같다(오른쪽 아래). */
export const FLOAT_DEFAULT_RIGHT_PX = 20;
export const FLOAT_DEFAULT_BOTTOM_PX = 90;
export const FLOAT_DEFAULT_WIDTH_PX = 400;
export const FLOAT_DEFAULT_HEIGHT_PX = 600;
export const FLOAT_DEFAULT_MAX_HEIGHT_RATIO = 0.8;
export const SIDE_DEFAULT_WIDTH_PX = 420;

export const FLOAT_RECT_STORAGE_KEY = "aimbase-chat:floating-rect";
export const SIDE_WIDTH_STORAGE_KEY = "aimbase-chat:side-width";

/** hi 가 lo 보다 작으면(화면이 최소 크기보다 작을 때) lo 를 준다. */
function clamp(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), Math.max(lo, hi));
}

function maxWidth(vp: Viewport): number {
  return Math.max(0, vp.width - 2 * EDGE_GAP_PX);
}

function maxHeight(vp: Viewport): number {
  return Math.max(0, vp.height - 2 * EDGE_GAP_PX);
}

export function defaultFloatingRect(vp: Viewport): Rect {
  const width = FLOAT_DEFAULT_WIDTH_PX;
  const height = Math.min(FLOAT_DEFAULT_HEIGHT_PX, vp.height * FLOAT_DEFAULT_MAX_HEIGHT_RATIO);
  return {
    left: vp.width - FLOAT_DEFAULT_RIGHT_PX - width,
    top: vp.height - FLOAT_DEFAULT_BOTTOM_PX - height,
    width,
    height,
  };
}

/**
 * 창 전체를 화면 가장자리 틈 안쪽에 넣는다. 화면이 창보다 작으면 줄인다(BIZ-286, BIZ-287).
 * minWidth 는 왼쪽 칸을 붙여 넓힌 창에서 그 칸 폭을 더한 최소 폭이다.
 */
export function fitFloating(r: Rect, vp: Viewport, minWidth = FLOAT_MIN_WIDTH_PX): Rect {
  const maxW = maxWidth(vp);
  const maxH = maxHeight(vp);
  const width = clamp(r.width, Math.min(minWidth, maxW), maxW);
  const height = clamp(r.height, Math.min(FLOAT_MIN_HEIGHT_PX, maxH), maxH);
  return {
    left: clamp(r.left, EDGE_GAP_PX, vp.width - EDGE_GAP_PX - width),
    top: clamp(r.top, EDGE_GAP_PX, vp.height - EDGE_GAP_PX - height),
    width,
    height,
  };
}

export function moveFloating(start: Rect, dx: number, dy: number, vp: Viewport, minWidth = FLOAT_MIN_WIDTH_PX): Rect {
  return fitFloating({ ...start, left: start.left + dx, top: start.top + dy }, vp, minWidth);
}

/** 끄는 변만 움직이고 반대쪽 변은 그대로 둔다. */
export function resizeFloating(
  start: Rect,
  edge: Edge,
  dx: number,
  dy: number,
  vp: Viewport,
  minWidth = FLOAT_MIN_WIDTH_PX,
): Rect {
  const minW = Math.min(minWidth, maxWidth(vp));
  const minH = Math.min(FLOAT_MIN_HEIGHT_PX, maxHeight(vp));
  let { left, top, width, height } = start;
  const right = left + width;
  const bottom = top + height;
  if (edge.includes("e")) {
    width = clamp(right + dx, left + minW, vp.width - EDGE_GAP_PX) - left;
  }
  if (edge.includes("w")) {
    left = clamp(left + dx, EDGE_GAP_PX, right - minW);
    width = right - left;
  }
  if (edge.includes("s")) {
    height = clamp(bottom + dy, top + minH, vp.height - EDGE_GAP_PX) - top;
  }
  if (edge.includes("n")) {
    top = clamp(top + dy, EDGE_GAP_PX, bottom - minH);
    height = bottom - top;
  }
  return fitFloating({ left, top, width, height }, vp, minWidth);
}

/** 왼쪽 칸을 붙인 창. 오른쪽 변은 그대로 두고 왼쪽으로 칸 폭만큼 넓힌다(CR-194 판 넓히기). */
export function widenForDock(r: Rect, paneWidth: number): Rect {
  return { ...r, left: r.left - paneWidth, width: r.width + paneWidth };
}

/** 넓힌 창에서 칸 폭을 뺀 대화 창. 기억하는 것은 이 값이다. */
export function narrowFromDock(r: Rect, paneWidth: number): Rect {
  return { ...r, left: r.left + paneWidth, width: r.width - paneWidth };
}

/** 옆 칸 위젯 폭. 오른쪽은 화면에 붙어 있어 왼쪽 틈만 남긴다(BIZ-287). */
export function fitSideWidth(width: number, vp: Viewport): number {
  const maxW = Math.max(0, vp.width - EDGE_GAP_PX);
  return clamp(width, Math.min(SIDE_MIN_WIDTH_PX, maxW), maxW);
}

/** 왼쪽 변을 dx 만큼 끈다. 왼쪽으로 끌면(dx 음수) 넓어진다. */
export function resizeSide(startWidth: number, dx: number, vp: Viewport): number {
  return fitSideWidth(startWidth - dx, vp);
}

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

function isLength(n: unknown): n is number {
  return typeof n === "number" && Number.isFinite(n);
}

/** 기억한 값이 없거나 깨졌으면 null. 저장소가 막혀 있어도 null(BIZ-288). */
export function readFloatingRect(store: StorageLike | null): Rect | null {
  try {
    const raw = store?.getItem(FLOAT_RECT_STORAGE_KEY);
    if (!raw) return null;
    const v: unknown = JSON.parse(raw);
    if (!v || typeof v !== "object") return null;
    const { left, top, width, height } = v as Record<string, unknown>;
    if (!isLength(left) || !isLength(top) || !isLength(width) || !isLength(height)) return null;
    if (width <= 0 || height <= 0) return null;
    return { left, top, width, height };
  } catch {
    return null;
  }
}

export function storeFloatingRect(store: StorageLike | null, r: Rect): void {
  try {
    store?.setItem(FLOAT_RECT_STORAGE_KEY, JSON.stringify({
      left: Math.round(r.left),
      top: Math.round(r.top),
      width: Math.round(r.width),
      height: Math.round(r.height),
    }));
  } catch {
    // 저장소를 막은 브라우저에서는 이번 화면에서만 유지한다.
  }
}

export function readSideWidth(store: StorageLike | null): number | null {
  try {
    const raw = store?.getItem(SIDE_WIDTH_STORAGE_KEY);
    if (!raw) return null;
    const v: unknown = JSON.parse(raw);
    return isLength(v) && v > 0 ? v : null;
  } catch {
    return null;
  }
}

export function storeSideWidth(store: StorageLike | null, width: number): void {
  try {
    store?.setItem(SIDE_WIDTH_STORAGE_KEY, String(Math.round(width)));
  } catch {
    // 저장소를 막은 브라우저에서는 이번 화면에서만 유지한다.
  }
}
