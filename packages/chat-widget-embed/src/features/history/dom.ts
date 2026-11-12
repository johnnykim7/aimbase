import type { WidgetContext } from "../../frame/context";
import { icon } from "../../icons";

export function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

export function button(cls: string, label: string, iconHtml?: string): HTMLButtonElement {
  const b = el("button", cls);
  b.type = "button";
  if (iconHtml) b.innerHTML = iconHtml;
  b.append(document.createTextNode(label));
  return b;
}

/** 글 없는 아이콘 단추. 도움말은 위젯 본체의 data-tip 으로 보인다. */
export function iconButton(iconHtml: string, label: string): HTMLButtonElement {
  const b = el("button", "ib sm");
  b.type = "button";
  b.innerHTML = iconHtml;
  b.setAttribute("aria-label", label);
  b.dataset.tip = label;
  return b;
}

/** 읽기 실패 자리. 「불러오지 못했습니다」와 다시 시도 단추(CR-178 4.2 14번). */
export function errorLine(t: { loadFailed: string; retry: string }, retry: () => void): HTMLElement {
  const line = el("div", "hist-err");
  line.append(el("span", undefined, t.loadFailed));
  const b = button("btn hist-retry", t.retry);
  b.addEventListener("click", (e) => {
    e.stopPropagation();
    retry();
  });
  line.append(b);
  return line;
}

export function spinner(small = false): HTMLElement {
  return el("span", small ? "hist-spin sm" : "hist-spin");
}

export interface Dialog {
  body: HTMLElement;
  foot: HTMLElement;
  close(): void;
}

/** 위젯 판 위에 겹치는 창. Esc, 바깥 누름, 닫기 단추로 닫는다. */
export function openDialog(
  ctx: WidgetContext,
  opts: { title?: string; closeLabel: string; onClose?: () => void },
): Dialog {
  const ov = el("div", "hist-ov");
  const box = el("div", "hist-dlg");
  box.setAttribute("role", "dialog");
  box.setAttribute("aria-modal", "true");
  const body = el("div", "hist-dbody");
  const foot = el("div", "hist-dfoot");
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    offEscape();
    ov.remove();
    opts.onClose?.();
  };
  if (opts.title !== undefined) {
    const head = el("div", "hist-dhead");
    const h = el("h3", undefined, opts.title);
    const x = iconButton(icon("x", 16), opts.closeLabel);
    x.addEventListener("click", close);
    head.append(h, x);
    box.append(head);
  }
  box.append(body, foot);
  ov.append(box);
  ov.addEventListener("pointerdown", (e) => {
    if (e.target === ov) close();
  });
  const offEscape = ctx.ui.onEscape(() => {
    close();
    return true;
  });
  ctx.ui.layer.appendChild(ov);
  return { body, foot, close };
}

/** 확인 창. 확인을 누르면 참, 그 밖으로 닫으면 거짓. */
export function confirmDialog(
  ctx: WidgetContext,
  texts: { message: string; ok: string; cancel: string; close: string },
): Promise<boolean> {
  return new Promise((resolve) => {
    let answer = false;
    const d = openDialog(ctx, { closeLabel: texts.close, onClose: () => resolve(answer) });
    d.body.append(el("p", "hist-confirm", texts.message));
    const cancel = button("btn", texts.cancel);
    const ok = button("btn hist-danger", texts.ok);
    cancel.addEventListener("click", () => d.close());
    ok.addEventListener("click", () => {
      answer = true;
      d.close();
    });
    d.foot.append(cancel, ok);
    ok.focus();
  });
}

/** 줄 메뉴. 단추 아래(자리가 없으면 위)에 띄우고 바깥 누름, Esc, 항목 선택으로 닫는다. */
export function openMenu(
  ctx: WidgetContext,
  container: HTMLElement,
  anchor: HTMLElement,
  items: Array<{ label: string; icon: string; danger?: boolean; onSelect: () => void }>,
): () => void {
  const menu = el("div", "pop on hist-menu");
  menu.setAttribute("role", "menu");
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    offEscape();
    document.removeEventListener("pointerdown", outside, true);
    menu.remove();
  };
  for (const it of items) {
    const b = el("button", it.danger ? "mi hist-danger-text" : "mi");
    b.type = "button";
    b.setAttribute("role", "menuitem");
    b.innerHTML = it.icon;
    b.append(document.createTextNode(it.label));
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      close();
      it.onSelect();
    });
    menu.append(b);
  }
  container.append(menu);
  const c = container.getBoundingClientRect();
  const a = anchor.getBoundingClientRect();
  const top = a.bottom - c.top + container.scrollTop + 2;
  menu.style.right = `${Math.max(4, c.right - a.right)}px`;
  menu.style.top = `${top}px`;
  if (a.bottom + menu.offsetHeight > c.bottom && a.top - menu.offsetHeight > c.top) {
    menu.style.top = `${a.top - c.top + container.scrollTop - menu.offsetHeight - 2}px`;
  }
  // 그림자 영역 안의 누름도 문서에서는 위젯 호스트로 보이므로 이벤트 경로로 메뉴 안인지 본다.
  const outside = (e: Event) => {
    if (!e.composedPath().includes(menu)) close();
  };
  document.addEventListener("pointerdown", outside, true);
  const offEscape = ctx.ui.onEscape(() => {
    close();
    return true;
  });
  return close;
}
