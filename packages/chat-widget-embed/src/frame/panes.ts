import type { LeftPaneHandle, LeftPaneSpec, SidePaneSpec } from "./context";

/** 위젯 폭이 이보다 넓으면 왼쪽 칸을 옆에 붙이고, 옆 창을 크게 보기 없이 연다. */
const WIDE_MIN_PX = 760;
/** CR-194: 붙여 넓힌 판(640px)이 오른쪽 여백과 함께 들어가는 브라우저 창 폭. 모자라면 칸을 덮어 연다(T3-3). */
export const DOCK_MIN_VIEWPORT_PX = 680;
/** CR-194: 붙인 왼쪽 칸의 폭. 판은 대화 폭에 이만큼 더 넓어진다(CR-198). */
export const DOCK_PANE_WIDTH_PX = 240;

export interface PanesHost {
  panel: HTMLElement;
  main: HTMLElement;
  header: HTMLElement;
  setExpanded(v: boolean): void;
  /** CR-194: 지금 왼쪽 칸을 붙여 판을 넓힐 수 있는지(떠 있는 창이고 크게 보기가 아님) */
  canDock(): boolean;
  /** CR-203: 붙일 자리를 잴 화면 폭. 없으면 이 창의 폭. 전용 페이지 안에서는 부모 화면 폭이다 */
  viewportWidth?(): number;
  /** CR-198: 왼쪽 칸을 붙여 판을 넓히거나 되돌릴 때. 판 폭은 사람이 정할 수 있어 넓히는 일은 받는 쪽이 한다 */
  dockChanged?(docked: boolean): void;
  closeLabel(): string;
  iconButton(iconHtml: string, label: string): HTMLButtonElement;
  leftToggleIcon: string;
  closeIcon: string;
}

interface LeftState {
  spec: LeftPaneSpec;
  el: HTMLElement;
  toggle: HTMLButtonElement;
  open: boolean;
  available: boolean;
}

interface SideState {
  spec: SidePaneSpec;
}

/** 왼쪽 칸과 옆 창(CR-177 T3-3 화면 컨셉 6, 7). */
export class Panes {
  private wide = false;
  /** 왼쪽 칸을 붙여 판이 넓어져 있는지. 넓어진 만큼은 대화 폭이 아니다 */
  private dockOn = false;
  private left: LeftState | null = null;
  private side: SideState | null = null;
  private readonly scrim: HTMLDivElement;
  private readonly sideEl: HTMLElement;
  private readonly sideHead: HTMLDivElement;
  private readonly sideBody: HTMLDivElement;
  private readonly sideFoot: HTMLDivElement;
  private observer: ResizeObserver | null = null;
  private readonly onViewport = () => this.refresh();

  constructor(private readonly host: PanesHost) {
    this.scrim = document.createElement("div");
    this.scrim.className = "lp-scrim";
    this.scrim.addEventListener("click", () => this.closeLeft());
    host.panel.appendChild(this.scrim);

    this.sideEl = document.createElement("aside");
    this.sideEl.className = "spane";
    this.sideEl.hidden = true;
    this.sideHead = document.createElement("div");
    this.sideHead.className = "spane-hd";
    this.sideBody = document.createElement("div");
    this.sideBody.className = "spane-body";
    this.sideFoot = document.createElement("div");
    this.sideFoot.className = "spane-ft";
    this.sideEl.append(this.sideHead, this.sideBody, this.sideFoot);
    host.panel.appendChild(this.sideEl);

    this.wide = host.panel.clientWidth >= WIDE_MIN_PX;
    host.panel.classList.toggle("narrow", !this.wide);
    if (typeof ResizeObserver !== "undefined") {
      this.observer = new ResizeObserver(() => this.onResize());
      this.observer.observe(host.panel);
    }
  }

  mountLeft(spec: LeftPaneSpec): LeftPaneHandle {
    if (this.left) throw new Error("left pane is already mounted");
    const el = document.createElement("aside");
    el.className = "lp";
    const body = document.createElement("div");
    body.className = "lp-body";
    el.appendChild(body);
    this.host.panel.insertBefore(el, this.host.panel.firstChild);
    const toggle = this.host.iconButton(this.host.leftToggleIcon, spec.label());
    toggle.addEventListener("click", (e) => {
      e.stopPropagation();
      if (this.left?.open) this.closeLeft();
      else this.openLeft();
    });
    this.host.header.insertBefore(toggle, this.host.header.firstChild);
    this.left = { spec, el, toggle, open: false, available: true };
    if (spec.dock && typeof window !== "undefined") window.addEventListener("resize", this.onViewport);
    try {
      spec.render(body);
    } catch (e) {
      console.error("[aimbase-chat] left pane render failed", e);
    }
    this.setLeftOpen(this.wide && !this.side);
    return {
      open: () => this.openLeft(),
      close: () => this.closeLeft(),
      isOpen: () => this.left?.open ?? false,
      setAvailable: (available) => this.setLeftAvailable(available),
      docks: () => this.left?.available === true && this.docked(),
    };
  }

  openLeft(): void {
    if (!this.left?.available) return;
    this.setLeftOpen(true);
  }

  /** CR-194: 크게 보기를 켜고 끄거나 브라우저 창 폭이 바뀌면 붙일지 덮을지 다시 정한다. */
  refresh(): void {
    if (this.left) this.setLeftOpen(this.left.open);
  }

  closeLeft(): void {
    this.setLeftOpen(false);
  }

  /** Esc: 겹쳐 열린 왼쪽 칸만 닫는다. 옆에 붙은 칸은 그대로다. */
  closeOverlayLeft(): boolean {
    if (!this.left?.open || this.wide || this.docked()) return false;
    this.closeLeft();
    return true;
  }

  openSide(spec: SidePaneSpec): void {
    if (this.side && this.side.spec.id !== spec.id) this.callOnClose(this.side.spec);
    if (!this.wide) this.host.setExpanded(true);
    this.side = { spec };
    this.closeLeft();
    this.renderSide();
    this.sideEl.hidden = false;
    this.host.panel.classList.add("has-side");
  }

  closeSide(): boolean {
    if (!this.side) return false;
    const spec = this.side.spec;
    this.side = null;
    this.sideEl.hidden = true;
    this.host.panel.classList.remove("has-side");
    this.sideHead.innerHTML = "";
    this.sideBody.innerHTML = "";
    this.sideFoot.innerHTML = "";
    this.callOnClose(spec);
    return true;
  }

  currentSideId(): string | null {
    return this.side?.spec.id ?? null;
  }

  /** 화면 언어가 바뀌면 틀이 그린 글만 다시 쓴다. 칸 안은 기능이 다시 그린다. */
  relabel(): void {
    if (this.left) {
      const label = this.left.spec.label();
      this.left.toggle.setAttribute("aria-label", label);
      this.left.toggle.setAttribute("data-tip", label);
    }
    if (this.side) this.renderSideHead();
  }

  destroy(): void {
    this.observer?.disconnect();
    this.observer = null;
    if (typeof window !== "undefined") window.removeEventListener("resize", this.onViewport);
  }

  private viewportWidth(): number {
    if (this.host.viewportWidth) return this.host.viewportWidth();
    return typeof window !== "undefined" ? window.innerWidth : 0;
  }

  /** 넓지 않은 판에서 왼쪽 칸을 덮지 않고 붙여 판을 넓히는 경우(CR-194 메뉴 화면). */
  private docked(): boolean {
    return !this.wide
      && this.left?.spec.dock === true
      && this.host.canDock()
      && this.viewportWidth() >= DOCK_MIN_VIEWPORT_PX;
  }

  private setLeftAvailable(available: boolean): void {
    if (!this.left) return;
    this.left.available = available;
    this.left.toggle.hidden = !available;
    if (!available) this.setLeftOpen(false);
  }

  private setLeftOpen(open: boolean): void {
    if (!this.left) return;
    const on = open && this.left.available;
    const docked = this.docked();
    const overlay = !this.wide && !docked;
    this.left.open = on;
    this.left.el.hidden = !on;
    this.left.el.classList.toggle("overlay", overlay);
    this.scrim.classList.toggle("on", on && overlay);
    const dockOn = on && docked;
    this.host.panel.classList.toggle("dock", dockOn);
    if (dockOn !== this.dockOn) {
      this.dockOn = dockOn;
      this.host.dockChanged?.(dockOn);
    }
    this.left.toggle.setAttribute("aria-pressed", String(on));
  }

  private onResize(): void {
    const width = this.host.panel.clientWidth;
    // 접힌 버블처럼 보이지 않는 동안은 폭이 0 이라 열린 칸을 그대로 둔다.
    if (width === 0) return;
    // 붙인 칸으로 넓어진 판을 넓은 판으로 보면 칸이 안으로 들어가며 판이 다시 좁아지기를 되풀이한다(CR-198).
    const chatWidth = this.dockOn ? width - DOCK_PANE_WIDTH_PX : width;
    const wide = chatWidth >= WIDE_MIN_PX;
    if (wide === this.wide) return;
    this.wide = wide;
    this.host.panel.classList.toggle("narrow", !wide);
    if (!wide) this.closeSide();
    this.setLeftOpen(wide && !this.side);
  }

  private renderSide(): void {
    if (!this.side) return;
    const spec = this.side.spec;
    this.renderSideHead();
    this.sideBody.innerHTML = "";
    this.sideFoot.innerHTML = "";
    try {
      spec.render(this.sideBody);
      if (spec.footer) spec.footer(this.sideFoot);
    } catch (e) {
      console.error(`[aimbase-chat] side pane ${spec.id} render failed`, e);
    }
    this.sideFoot.hidden = !spec.footer;
  }

  private renderSideHead(): void {
    if (!this.side) return;
    const spec = this.side.spec;
    this.sideHead.innerHTML = "";
    if (spec.icon) {
      const ic = document.createElement("span");
      ic.className = "spane-ic";
      ic.innerHTML = spec.icon;
      this.sideHead.appendChild(ic);
    }
    const title = document.createElement("span");
    title.className = "spane-ttl";
    title.textContent = spec.title();
    title.title = title.textContent ?? "";
    this.sideHead.appendChild(title);
    for (const tool of spec.tools ?? []) this.sideHead.appendChild(tool);
    const close = this.host.iconButton(this.host.closeIcon, this.host.closeLabel());
    close.addEventListener("click", () => this.closeSide());
    this.sideHead.appendChild(close);
  }

  private callOnClose(spec: SidePaneSpec): void {
    try {
      spec.onClose?.();
    } catch (e) {
      console.error(`[aimbase-chat] side pane ${spec.id} onClose failed`, e);
    }
  }
}
