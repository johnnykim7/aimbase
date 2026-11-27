import type { FrameBridge } from "../types";
import { DRAG_START_PX, INTERACTIVE } from "./window-drag";

/**
 * CR-203: 전용 페이지 안 떠 있는 창의 머리줄 끌기. 창을 옮기는 것은 부모 화면이다.
 * 누른 머리줄에 포인터를 붙잡고 화면 좌표를 보낸다. 상자가 움직이면 창 안 좌표가 따라 바뀌므로 화면 좌표를 쓴다.
 */
export class FrameHeaderDrag {
  private active = false;
  private readonly onDown = (e: PointerEvent) => this.begin(e);

  constructor(
    private readonly header: HTMLElement,
    private readonly send: FrameBridge["drag"],
    private readonly canStart: () => boolean,
  ) {
    header.addEventListener("pointerdown", this.onDown);
  }

  destroy(): void {
    this.header.removeEventListener("pointerdown", this.onDown);
  }

  private begin(e: PointerEvent): void {
    if (this.active || !this.canStart() || !e.isPrimary) return;
    if (e.pointerType === "mouse" && e.button !== 0) return;
    if ((e.target as Element | null)?.closest?.(INTERACTIVE)) return;
    e.preventDefault();
    const header = this.header;
    const id = e.pointerId;
    const x0 = e.screenX;
    const y0 = e.screenY;
    let started = false;
    this.active = true;
    try {
      header.setPointerCapture(id);
    } catch {
      // 붙잡지 못하는 브라우저는 포인터가 머리줄 위에 있는 동안만 따라간다.
    }
    const onMove = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      if (!started) {
        if (Math.hypot(ev.screenX - x0, ev.screenY - y0) <= DRAG_START_PX) return;
        started = true;
        this.send("start", x0, y0);
      }
      this.send("move", ev.screenX, ev.screenY);
    };
    const end = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      header.removeEventListener("pointermove", onMove);
      header.removeEventListener("pointerup", end);
      header.removeEventListener("pointercancel", end);
      header.removeEventListener("lostpointercapture", end);
      try {
        header.releasePointerCapture(id);
      } catch {
        // 이미 풀렸다.
      }
      this.active = false;
      if (started) this.send("end", ev.screenX, ev.screenY);
    };
    header.addEventListener("pointermove", onMove);
    header.addEventListener("pointerup", end);
    header.addEventListener("pointercancel", end);
    header.addEventListener("lostpointercapture", end);
  }
}
