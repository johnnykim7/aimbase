import type { LeftPaneHandle, LeftPaneSpec, SidePaneSpec } from "./context";

/** 위젯 폭이 이보다 넓으면 왼쪽 칸을 옆에 붙이고, 옆 창을 크게 보기 없이 연다. */
const WIDE_MIN_PX = 760;

export interface PanesHost {
  panel: HTMLElement;
  main: HTMLElement;
  header: HTMLElement;
  setExpanded(v: boolean): void;
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
}

interface SideState {
  spec: SidePaneSpec;
}

/** 왼쪽 칸과 옆 창(CR-177 T3-3 화면 컨셉 6, 7). */
export class Panes {
  private wide = false;
  private left: LeftState | null = null;
  private side: SideState | null = null;
  private readonly scrim: HTMLDivElement;
  private readonly sideEl: HTMLElement;
  private readonly sideHead: HTMLDivElement;
  private readonly sideBody: HTMLDivElement;
  private readonly sideFoot: HTMLDivElement;
  private observer: ResizeObserver | null = null;

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
    this.left = { spec, el, toggle, open: false };
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
    };
  }

  openLeft(): void {
    this.setLeftOpen(true);
  }

  closeLeft(): void {
    this.setLeftOpen(false);
  }

  /** Esc: 겹쳐 열린 왼쪽 칸만 닫는다. 옆에 붙은 칸은 그대로다. */
  closeOverlayLeft(): boolean {
    if (!this.left?.open || this.wide) return false;
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
  }

  private setLeftOpen(open: boolean): void {
    if (!this.left) return;
    this.left.open = open;
    this.left.el.hidden = !open;
    this.left.el.classList.toggle("overlay", !this.wide);
    this.scrim.classList.toggle("on", open && !this.wide);
    this.left.toggle.setAttribute("aria-pressed", String(open));
  }

  private onResize(): void {
    const width = this.host.panel.clientWidth;
    // 접힌 버블처럼 보이지 않는 동안은 폭이 0 이라 열린 칸을 그대로 둔다.
    if (width === 0) return;
    const wide = width >= WIDE_MIN_PX;
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
