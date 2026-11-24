import { DOCK_PANE_WIDTH_PX } from "./panes";
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
} from "./window-rect";

/** 머리줄을 누르고 이만큼 넘게 움직여야 옮기기로 본다. 그냥 누른 것과 가른다. */
const DRAG_START_PX = 3;

/** 머리줄에서 이것을 누른 것은 옮기기가 아니다. 기능이 머리줄에 붙인 단추도 여기 걸린다. */
const INTERACTIVE = "button, a, input, textarea, select, label, [role='button'], [contenteditable='true']";

/** 크게 보기에서는 CSS 가 화면을 채운다. 붙여 둔 자리가 남으면 그것을 이기므로 뗀다. */
const PLACED_PROPS = ["left", "top", "right", "bottom", "width", "height", "max-height"];

export type WindowMode = "floating" | "side";

export interface WindowDragHost {
  mode: WindowMode;
  /** 잡는 자리 틀을 붙일 곳. 판 안에 두면 판의 overflow 에 잘린다 */
  root: HTMLElement;
  panel: HTMLElement;
  header: HTMLElement;
}

function viewport(): Viewport {
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

/** 떠 있는 위젯 옮기기와 크기 바꾸기, 옆 칸 위젯 폭 바꾸기(CR-198). */
export class WindowDrag {
  private readonly store = browserStorage();
  private readonly frame: HTMLDivElement;
  /** 사람이 정한 자리와 크기. null 이면 처음 자리다 */
  private floating: Rect | null;
  private sideWidth: number | null;
  /** 지금 화면에 맞춰 보이는 값. 끌기는 여기서 시작한다 */
  private shown: Rect | null = null;
  private shownSide = 0;
  private open = false;
  private expanded = false;
  /** 왼쪽 칸을 붙여 창이 그 폭만큼 넓어져 있다(CR-194). 기억하는 자리와 크기는 칸을 뺀 대화 창이다 */
  private docked = false;
  private dragging = false;
  private readonly onViewport = () => this.apply();

  constructor(private readonly host: WindowDragHost) {
    this.floating = host.mode === "floating" ? readFloatingRect(this.store) : null;
    this.sideWidth = host.mode === "side" ? readSideWidth(this.store) : null;

    this.frame = document.createElement("div");
    this.frame.className = `win-frame ${host.mode}`;
    this.frame.setAttribute("aria-hidden", "true");
    const edges: readonly Edge[] = host.mode === "floating" ? EDGES : ["w"];
    for (const edge of edges) {
      const handle = document.createElement("div");
      handle.className = `rz rz-${edge}`;
      handle.addEventListener("pointerdown", (e) => this.beginResize(e, edge, handle));
      this.frame.appendChild(handle);
    }
    host.root.appendChild(this.frame);

    if (host.mode === "floating") {
      host.header.addEventListener("pointerdown", (e) => this.beginMove(e));
    }
    window.addEventListener("resize", this.onViewport);
    this.apply();
  }

  setOpen(v: boolean): void {
    this.open = v;
    this.apply();
  }

  setExpanded(v: boolean): void {
    this.expanded = v;
    this.apply();
  }

  /** 끄는 중이면 손을 뗄 때 맞춘다. 끄는 동안 창이 튀지 않게 한다 */
  setDocked(v: boolean): void {
    if (this.docked === v) return;
    this.docked = v;
    if (!this.dragging) this.apply();
  }

  destroy(): void {
    window.removeEventListener("resize", this.onViewport);
    this.frame.remove();
  }

  /** 기억한 값을 지금 화면에 맞춰 보인다. 맞춘 값은 저장하지 않는다(BIZ-288). */
  private apply(): void {
    const active = this.open && !this.expanded;
    this.frame.hidden = !active;
    this.host.panel.classList.toggle("movable", active && this.host.mode === "floating");
    if (this.expanded) {
      for (const p of PLACED_PROPS) this.host.panel.style.removeProperty(p);
      return;
    }
    const vp = viewport();
    if (this.host.mode === "floating") {
      const base = this.floating ?? defaultFloatingRect(vp);
      this.show(fitFloating(this.docked ? widenForDock(base, DOCK_PANE_WIDTH_PX) : base, vp, this.minWidth()));
    } else {
      this.showSide(fitSideWidth(this.sideWidth ?? SIDE_DEFAULT_WIDTH_PX, vp));
    }
  }

  private show(r: Rect): void {
    this.shown = r;
    for (const s of [this.host.panel.style, this.frame.style]) {
      s.left = `${r.left}px`;
      s.top = `${r.top}px`;
      s.width = `${r.width}px`;
      s.height = `${r.height}px`;
    }
    const s = this.host.panel.style;
    s.right = "auto";
    s.bottom = "auto";
    s.maxHeight = "none";
  }

  private showSide(width: number): void {
    this.shownSide = width;
    this.host.panel.style.width = `${width}px`;
    this.frame.style.width = `${width}px`;
  }

  private minWidth(): number {
    return FLOAT_MIN_WIDTH_PX + (this.docked ? DOCK_PANE_WIDTH_PX : 0);
  }

  private canStart(e: PointerEvent): boolean {
    if (!this.open || this.expanded || this.dragging || !e.isPrimary) return false;
    return e.pointerType !== "mouse" || e.button === 0;
  }

  private beginMove(e: PointerEvent): void {
    if (!this.canStart(e)) return;
    const target = e.target as Element | null;
    if (target?.closest?.(INTERACTIVE)) return;
    const start = this.shown;
    if (!start) return;
    this.track(e, this.host.header, false,
      (dx, dy) => this.show(moveFloating(start, dx, dy, viewport(), this.minWidth())),
      () => this.commitFloating());
  }

  private beginResize(e: PointerEvent, edge: Edge, handle: HTMLElement): void {
    if (!this.canStart(e)) return;
    if (this.host.mode === "floating") {
      const start = this.shown;
      if (!start) return;
      this.track(e, handle, true,
        (dx, dy) => this.show(resizeFloating(start, edge, dx, dy, viewport(), this.minWidth())),
        () => this.commitFloating());
    } else {
      const start = this.shownSide;
      this.track(e, handle, true,
        (dx) => this.showSide(resizeSide(start, dx, viewport())),
        () => this.commitSide());
    }
  }

  /**
   * 누른 곳에 포인터를 붙잡아 손을 뗄 때까지 따라간다. immediate 가 아니면 조금 움직인 뒤부터 옮긴다.
   * 누르자마자 붙잡아야 머리줄 밖으로 빠르게 끌어도 놓치지 않는다.
   */
  private track(
    e: PointerEvent,
    target: HTMLElement,
    immediate: boolean,
    move: (dx: number, dy: number) => void,
    commit: () => void,
  ): void {
    e.preventDefault();
    const id = e.pointerId;
    const x0 = e.clientX;
    const y0 = e.clientY;
    let started = false;
    this.dragging = true;
    try {
      target.setPointerCapture(id);
    } catch {
      // 붙잡지 못하는 브라우저는 포인터가 그 요소 위에 있는 동안만 따라간다.
    }
    const begin = () => {
      started = true;
      this.host.root.classList.add("win-dragging");
    };
    if (immediate) begin();
    const onMove = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      const dx = ev.clientX - x0;
      const dy = ev.clientY - y0;
      if (!started) {
        if (Math.hypot(dx, dy) <= DRAG_START_PX) return;
        begin();
      }
      move(dx, dy);
    };
    const end = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      target.removeEventListener("pointermove", onMove);
      target.removeEventListener("pointerup", end);
      target.removeEventListener("pointercancel", end);
      target.removeEventListener("lostpointercapture", end);
      try {
        target.releasePointerCapture(id);
      } catch {
        // 이미 풀렸다.
      }
      this.dragging = false;
      this.host.root.classList.remove("win-dragging");
      if (started && !this.expanded) commit();
      // 끄는 동안 칸이 붙거나 떨어졌으면 여기서 맞춘다.
      this.apply();
    };
    target.addEventListener("pointermove", onMove);
    target.addEventListener("pointerup", end);
    target.addEventListener("pointercancel", end);
    target.addEventListener("lostpointercapture", end);
  }

  private commitFloating(): void {
    if (!this.shown) return;
    const r = this.docked ? narrowFromDock(this.shown, DOCK_PANE_WIDTH_PX) : this.shown;
    this.floating = r;
    storeFloatingRect(this.store, r);
  }

  private commitSide(): void {
    this.sideWidth = this.shownSide;
    storeSideWidth(this.store, this.shownSide);
  }
}
