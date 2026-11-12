import type { MenuItemSpec, MenuRowSpec, MenuSlot } from "./context";

type Entry = { kind: "item"; spec: MenuItemSpec } | { kind: "row"; spec: MenuRowSpec };

function isVisible(visible?: () => boolean): boolean {
  if (!visible) return true;
  try {
    return visible();
  } catch (e) {
    console.error("[aimbase-chat] menu visible() failed", e);
    return false;
  }
}

/**
 * + 메뉴와 더 보기 메뉴. 열 때마다 항목을 지금 언어로 다시 그린다.
 * 보이는 항목이 하나도 없으면 단추째 숨긴다(BIZ-162).
 */
export class Menu implements MenuSlot {
  readonly pop: HTMLDivElement;
  private readonly entries: Entry[] = [];

  constructor(
    private readonly button: HTMLButtonElement,
    private readonly container: HTMLElement,
    private readonly placement: "below-end" | "above-start",
  ) {
    this.pop = document.createElement("div");
    this.pop.className = "pop menu";
    this.pop.setAttribute("role", "menu");
    this.pop.addEventListener("click", (e) => e.stopPropagation());
    container.appendChild(this.pop);
    button.addEventListener("click", (e) => {
      e.stopPropagation();
      if (this.isOpen()) this.close();
      else this.open();
    });
  }

  addItem(spec: MenuItemSpec): void {
    this.entries.push({ kind: "item", spec });
    this.refreshButton();
  }

  addRow(spec: MenuRowSpec): void {
    this.entries.push({ kind: "row", spec });
    this.refreshButton();
  }

  isOpen(): boolean {
    return this.pop.classList.contains("on");
  }

  close(): void {
    this.pop.classList.remove("on");
    this.button.setAttribute("aria-expanded", "false");
  }

  /** 다른 메뉴를 닫는 것은 부르는 쪽(위젯 본체)이 미리 한다. */
  open(): void {
    this.onBeforeOpen?.();
    this.render();
    this.position();
    this.pop.classList.add("on");
    this.button.setAttribute("aria-expanded", "true");
  }

  onBeforeOpen: (() => void) | null = null;

  refreshButton(): void {
    this.button.hidden = this.visibleEntries().length === 0;
    if (this.button.hidden) this.close();
  }

  private visibleEntries(): Entry[] {
    return this.entries
      .filter((e) => isVisible(e.spec.visible))
      .sort((a, b) => (a.spec.group ?? 0) - (b.spec.group ?? 0) || a.spec.order - b.spec.order);
  }

  private render(): void {
    this.pop.innerHTML = "";
    let lastGroup: number | null = null;
    for (const entry of this.visibleEntries()) {
      const group = entry.spec.group ?? 0;
      if (lastGroup !== null && group !== lastGroup) {
        const sep = document.createElement("div");
        sep.className = "sep";
        this.pop.appendChild(sep);
      }
      lastGroup = group;
      try {
        this.pop.appendChild(entry.kind === "item" ? this.itemEl(entry.spec) : entry.spec.render());
      } catch (e) {
        console.error(`[aimbase-chat] menu entry ${entry.spec.id} failed`, e);
      }
    }
  }

  private itemEl(spec: MenuItemSpec): HTMLButtonElement {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "mi";
    b.setAttribute("role", "menuitem");
    const ic = document.createElement("span");
    ic.className = "mic";
    if (spec.icon) ic.innerHTML = spec.icon;
    const label = document.createElement("span");
    label.className = "ml";
    label.textContent = spec.label();
    b.append(ic, label);
    const hint = spec.hint?.();
    if (hint) {
      const h = document.createElement("span");
      h.className = "mh";
      h.textContent = hint;
      b.appendChild(h);
    }
    b.addEventListener("click", () => {
      this.close();
      spec.onSelect();
    });
    return b;
  }

  private position(): void {
    const box = this.container.getBoundingClientRect();
    const btn = this.button.getBoundingClientRect();
    const s = this.pop.style;
    s.top = s.bottom = s.left = s.right = "";
    if (this.placement === "below-end") {
      s.top = `${btn.bottom - box.top + 4}px`;
      s.right = `${Math.max(4, box.right - btn.right)}px`;
    } else {
      s.bottom = `${box.bottom - btn.top + 6}px`;
      s.left = `${Math.max(4, btn.left - box.left)}px`;
    }
  }
}
