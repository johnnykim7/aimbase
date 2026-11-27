/**
 * CR-203: 연결 요소(<aimbase-chat-frame>)가 붙이는 앱 화면에 그리는 상자. iframe 을 담고,
 * 자리와 크기, 그림자, 뒷막, 잡는 자리를 맡는다. 계산은 떠 있는 위젯과 같은 window-rect 를 쓴다(CR-198).
 */
import type { DisplayMode } from "../types";
import type { DragPhase, FrameState } from "../frame/frame-protocol";
import { DOCK_PANE_WIDTH_PX } from "../frame/panes";
import { RESIZE_HANDLE_CSS } from "../frame/resize-handle-css";
import {
  EDGES,
  FLOAT_MIN_WIDTH_PX,
  SIDE_DEFAULT_WIDTH_PX,
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
  type Edge,
  type Rect,
  type StorageLike,
  type Viewport,
} from "../frame/window-rect";

/** 버블 단추. 위젯 CSS 의 .bubble-btn 과 같다 */
export const BUBBLE_SIZE_PX = 56;
export const BUBBLE_OFFSET_PX = 20;

export type HostBox =
  | { kind: "hidden" }
  | { kind: "bubble"; rect: Rect }
  | { kind: "floating"; rect: Rect }
  | { kind: "side"; width: number }
  | { kind: "inline" }
  | { kind: "expanded" };

export interface HostLayout {
  display: DisplayMode;
  state: FrameState;
  viewport: Viewport;
  /** 기억한 떠 있는 창(칸을 뺀 대화 창). 없으면 처음 자리 */
  floating: Rect | null;
  sideWidth: number | null;
}

export function bubbleRect(vp: Viewport): Rect {
  return {
    left: vp.width - BUBBLE_OFFSET_PX - BUBBLE_SIZE_PX,
    top: vp.height - BUBBLE_OFFSET_PX - BUBBLE_SIZE_PX,
    width: BUBBLE_SIZE_PX,
    height: BUBBLE_SIZE_PX,
  };
}

export function floatingMinWidth(docked: boolean): number {
  return FLOAT_MIN_WIDTH_PX + (docked ? DOCK_PANE_WIDTH_PX : 0);
}

/** display 와 상태로 상자를 정한다. 크게 보기는 CSS 가 가장자리 28px 안쪽을 채운다. */
export function hostBox(l: HostLayout): HostBox {
  const { display, state, viewport: vp } = l;
  if (display === "bubble" && !state.open) return { kind: "bubble", rect: bubbleRect(vp) };
  if (!state.open) return { kind: "hidden" };
  if (state.expanded) return { kind: "expanded" };
  if (display === "bubble") {
    const base = l.floating ?? defaultFloatingRect(vp);
    const placed = state.docked ? widenForDock(base, DOCK_PANE_WIDTH_PX) : base;
    return { kind: "floating", rect: fitFloating(placed, vp, floatingMinWidth(state.docked)) };
  }
  if (display === "panel") return { kind: "side", width: fitSideWidth(l.sideWidth ?? SIDE_DEFAULT_WIDTH_PX, vp) };
  return { kind: "inline" };
}

/** 전용 페이지가 보낸 화면 좌표 차이만큼 옮긴다. 화면 좌표라 상자가 움직여도 기준이 흔들리지 않는다. */
export function dragFloating(start: Rect, from: { x: number; y: number }, to: { x: number; y: number }, vp: Viewport, docked: boolean): Rect {
  return moveFloating(start, to.x - from.x, to.y - from.y, vp, floatingMinWidth(docked));
}

export const HOST_CSS = `
:host { display: block; }
:host([data-display="inline"]) { height: 100%; }
:host([data-display="inline"]) .wrap { height: 100%; }
.box { position: fixed; z-index: 9999; overflow: hidden; border-radius: 10px; box-shadow: 0 10px 30px rgba(0,0,0,.18); }
.box.bubble { z-index: 9998; border-radius: 50%; box-shadow: 0 4px 12px rgba(0,0,0,.15); }
.box.side { top: 0; right: 0; bottom: 0; box-shadow: none; }
.box.inline { position: relative; display: flex; width: 100%; height: 100%; min-height: 400px; box-shadow: none; z-index: auto; }
.box.inline iframe { flex: 1 1 auto; height: auto; }
.box.expanded { position: fixed; inset: 28px; width: auto; height: auto; z-index: 10000; box-shadow: 0 24px 60px rgba(0,0,0,.22); }
.box.hidden { display: none; }
.box iframe { display: block; width: 100%; height: 100%; border: 0; background: transparent; color-scheme: normal; }
.backdrop { display: none; position: fixed; inset: 0; background: rgba(24,24,27,.32); z-index: 9999; }
.backdrop.on { display: block; }
${RESIZE_HANDLE_CSS}
.resizing .box iframe { pointer-events: none; }
.resizing { user-select: none; }
`;

export function viewport(): Viewport {
  const d = document.documentElement;
  return { width: d.clientWidth || window.innerWidth, height: d.clientHeight || window.innerHeight };
}

function browserStorage(): StorageLike | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

const PLACED_PROPS = ["left", "top", "width", "height"] as const;

/** 상자 DOM. 기억은 붙이는 앱 출처의 브라우저 저장소에 떠 있는 위젯과 같은 이름으로 남는다(BIZ-288). */
export class HostWindow {
  readonly wrap: HTMLDivElement;
  readonly box: HTMLDivElement;
  private readonly frame: HTMLDivElement;
  private readonly backdrop: HTMLDivElement;
  private readonly store = browserStorage();
  private floating: Rect | null;
  private sideWidth: number | null;
  private state: FrameState;
  private shown: HostBox = { kind: "hidden" };
  private move: { rect: Rect; x: number; y: number } | null = null;
  private resizing = false;
  private readonly onViewport = () => this.apply();

  constructor(
    parent: ShadowRoot,
    private readonly display: DisplayMode,
    iframe: HTMLIFrameElement,
    onBackdrop: () => void,
  ) {
    this.floating = display === "bubble" ? readFloatingRect(this.store) : null;
    this.sideWidth = display === "panel" ? readSideWidth(this.store) : null;
    this.state = { open: display !== "bubble", expanded: false, docked: false };

    this.wrap = document.createElement("div");
    this.wrap.className = "wrap";
    this.backdrop = document.createElement("div");
    this.backdrop.className = "backdrop";
    this.backdrop.addEventListener("click", onBackdrop);
    this.box = document.createElement("div");
    this.box.className = "box";
    this.box.appendChild(iframe);
    this.frame = document.createElement("div");
    this.frame.className = `win-frame ${display === "panel" ? "side" : "floating"}`;
    this.frame.setAttribute("aria-hidden", "true");
    const edges: readonly Edge[] = display === "bubble" ? EDGES : display === "panel" ? ["w"] : [];
    for (const edge of edges) {
      const handle = document.createElement("div");
      handle.className = `rz rz-${edge}`;
      handle.addEventListener("pointerdown", (e) => this.beginResize(e, edge, handle));
      this.frame.appendChild(handle);
    }
    this.wrap.append(this.backdrop, this.box, this.frame);
    parent.appendChild(this.wrap);
    window.addEventListener("resize", this.onViewport);
    this.apply();
  }

  setState(state: FrameState): void {
    this.state = state;
    if (!this.move && !this.resizing) this.apply();
  }

  /** 전용 페이지 머리줄 끌기(CR-203). 떠 있는 창만 옮긴다 */
  drag(phase: DragPhase, x: number, y: number): void {
    if (phase === "start") {
      if (this.shown.kind !== "floating") return;
      this.move = { rect: this.shown.rect, x, y };
      return;
    }
    const start = this.move;
    if (!start) return;
    if (phase === "move") {
      this.showFloating(dragFloating(start.rect, start, { x, y }, viewport(), this.state.docked));
      return;
    }
    this.move = null;
    this.commitFloating();
    this.apply();
  }

  destroy(): void {
    window.removeEventListener("resize", this.onViewport);
    this.wrap.remove();
  }

  private apply(): void {
    const b = hostBox({
      display: this.display,
      state: this.state,
      viewport: viewport(),
      floating: this.floating,
      sideWidth: this.sideWidth,
    });
    this.shown = b;
    this.box.className = `box ${b.kind}`;
    this.backdrop.classList.toggle("on", b.kind === "expanded");
    this.frame.hidden = !(b.kind === "floating" || b.kind === "side");
    for (const p of PLACED_PROPS) {
      this.box.style.removeProperty(p);
      this.frame.style.removeProperty(p);
    }
    if (b.kind === "bubble" || b.kind === "floating") this.place(b.rect);
    if (b.kind === "side") this.placeSide(b.width);
  }

  private place(r: Rect): void {
    for (const s of [this.box.style, this.frame.style]) {
      s.left = `${r.left}px`;
      s.top = `${r.top}px`;
      s.width = `${r.width}px`;
      s.height = `${r.height}px`;
    }
  }

  private placeSide(width: number): void {
    this.box.style.width = `${width}px`;
    this.frame.style.width = `${width}px`;
  }

  private showFloating(r: Rect): void {
    this.shown = { kind: "floating", rect: r };
    this.place(r);
  }

  private beginResize(e: PointerEvent, edge: Edge, handle: HTMLElement): void {
    if (this.resizing || this.move || !e.isPrimary) return;
    if (e.pointerType === "mouse" && e.button !== 0) return;
    const shown = this.shown;
    if (shown.kind !== "floating" && shown.kind !== "side") return;
    e.preventDefault();
    const id = e.pointerId;
    const x0 = e.clientX;
    const y0 = e.clientY;
    this.resizing = true;
    this.wrap.classList.add("resizing");
    try {
      handle.setPointerCapture(id);
    } catch {
      // 붙잡지 못하는 브라우저는 포인터가 잡는 자리 위에 있는 동안만 따라간다.
    }
    const onMove = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      const dx = ev.clientX - x0;
      const dy = ev.clientY - y0;
      if (shown.kind === "floating") {
        this.showFloating(resizeFloating(shown.rect, edge, dx, dy, viewport(), floatingMinWidth(this.state.docked)));
      } else {
        const width = resizeSide(shown.width, dx, viewport());
        this.shown = { kind: "side", width };
        this.placeSide(width);
      }
    };
    const end = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      handle.removeEventListener("pointermove", onMove);
      handle.removeEventListener("pointerup", end);
      handle.removeEventListener("pointercancel", end);
      handle.removeEventListener("lostpointercapture", end);
      try {
        handle.releasePointerCapture(id);
      } catch {
        // 이미 풀렸다.
      }
      this.resizing = false;
      this.wrap.classList.remove("resizing");
      if (this.shown.kind === "floating") this.commitFloating();
      if (this.shown.kind === "side") {
        this.sideWidth = this.shown.width;
        storeSideWidth(this.store, this.shown.width);
      }
      this.apply();
    };
    handle.addEventListener("pointermove", onMove);
    handle.addEventListener("pointerup", end);
    handle.addEventListener("pointercancel", end);
    handle.addEventListener("lostpointercapture", end);
  }

  private commitFloating(): void {
    if (this.shown.kind !== "floating") return;
    const r = this.state.docked ? narrowFromDock(this.shown.rect, DOCK_PANE_WIDTH_PX) : this.shown.rect;
    this.floating = r;
    storeFloatingRect(this.store, r);
  }
}
