import { icon, svgIcon } from "../../icons";
import type { VoiceStrings } from "./config";

export type Phase = "connecting" | "listening" | "thinking" | "speaking";

/** 말 끊기 단추 그림(손바닥) */
const HAND = svgIcon(
  '<path d="M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2"/><path d="M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2"/><path d="M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"/>',
);

export const BAR_CSS = `
.vc-bar {
  position: absolute; left: 12px; right: 12px; bottom: 10px; max-width: 760px; margin: 0 auto; min-height: 76px;
  display: flex; align-items: center; gap: 10px; padding: 10px 12px; z-index: 11; box-sizing: border-box;
  background: var(--aimbase-bg); border: 1px solid var(--aimbase-border-strong); border-radius: 14px;
  box-shadow: 0 1px 3px rgba(0,0,0,.04); container-type: inline-size;
}
.vc-dot {
  width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; background: var(--aimbase-muted);
  transform: scale(calc(1 + var(--vc-lv, 0) * .8)); transition: transform .08s linear;
}
.vc-bar[data-phase="listening"] .vc-dot { background: var(--aimbase-danger); }
.vc-bar[data-phase="speaking"] .vc-dot { background: var(--aimbase-primary); animation: vc-pulse 1s ease-in-out infinite; }
.vc-bar[data-phase="connecting"] .vc-dot, .vc-bar[data-phase="thinking"] .vc-dot { animation: vc-pulse 1.2s ease-in-out infinite; }
@keyframes vc-pulse { 0%, 100% { opacity: .45; } 50% { opacity: 1; } }
.vc-st { font-size: 13px; font-weight: 600; color: var(--aimbase-fg); white-space: nowrap; flex-shrink: 0; }
.vc-tx {
  flex: 1; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 13px;
  direction: rtl; text-align: left;
}
.vc-tx bdi { direction: ltr; }
.vc-tx .vc-fin { color: var(--aimbase-fg); font-weight: 600; }
.vc-tx .vc-buf { color: var(--aimbase-muted); }
.vc-sp { flex: 1; }
.vc-bar[data-phase="listening"] .vc-sp { display: none; }
.vc-bar:not([data-phase="listening"]) .vc-tx { display: none; }
.vc-btn {
  display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; flex-shrink: 0;
  border: 1px solid var(--aimbase-border); border-radius: 8px; background: transparent; color: var(--aimbase-fg);
  font: inherit; font-size: 13px; cursor: pointer;
}
.vc-btn:hover { background: var(--aimbase-chip-bg); }
.vc-btn[hidden] { display: none; }
@container (max-width: 360px) {
  .vc-btn { padding: 0 7px; }
  .vc-btn .vc-lbl { display: none; }
}
`;

/** 음성 대화 줄(T3-3 화면 컨셉 4, 5, 6). 음성 대화 동안 입력칸 자리를 덮는다. */
export class VoiceBar {
  private readonly el: HTMLDivElement;
  private readonly dot: HTMLSpanElement;
  private readonly status: HTMLSpanElement;
  private readonly fin: HTMLSpanElement;
  private readonly buf: HTMLSpanElement;
  private readonly interruptBtn: HTMLButtonElement;
  private readonly endBtn: HTMLButtonElement;
  private current: Phase = "connecting";

  constructor(
    private readonly layer: HTMLElement,
    private readonly t: VoiceStrings,
    on: { interrupt: () => void; end: () => void },
  ) {
    this.el = document.createElement("div");
    this.el.className = "vc-bar";
    this.el.setAttribute("role", "group");
    this.dot = document.createElement("span");
    this.dot.className = "vc-dot";
    this.status = document.createElement("span");
    this.status.className = "vc-st";
    this.status.setAttribute("role", "status");
    this.status.setAttribute("aria-live", "polite");
    const tx = document.createElement("span");
    tx.className = "vc-tx";
    const bdi = document.createElement("bdi");
    this.fin = document.createElement("span");
    this.fin.className = "vc-fin";
    this.buf = document.createElement("span");
    this.buf.className = "vc-buf";
    bdi.append(this.fin, this.buf);
    tx.appendChild(bdi);
    const sp = document.createElement("span");
    sp.className = "vc-sp";
    this.interruptBtn = this.button(HAND, on.interrupt);
    this.endBtn = this.button(icon("x"), on.end);
    this.el.append(this.dot, this.status, tx, sp, this.interruptBtn, this.endBtn);
    this.relabel();
  }

  show(): void {
    if (!this.el.isConnected) this.layer.appendChild(this.el);
    this.phase("connecting");
    this.transcript("", "");
  }

  hide(): void {
    this.el.remove();
    this.level(0);
  }

  phase(p: Phase): void {
    this.current = p;
    this.el.dataset.phase = p;
    this.status.textContent = this.phaseText(p);
    this.interruptBtn.hidden = p !== "thinking" && p !== "speaking";
    if (p !== "listening") this.level(0);
  }

  transcript(confirmed: string, pending: string): void {
    this.fin.textContent = confirmed;
    this.buf.textContent = pending && confirmed ? ` ${pending}` : pending;
  }

  /** 0 은 조용함, 1 은 말소리 기준 크기 이상 */
  level(ratio: number): void {
    this.dot.style.setProperty("--vc-lv", String(ratio));
  }

  relabel(): void {
    this.status.textContent = this.phaseText(this.current);
    this.label(this.interruptBtn, this.t.interrupt);
    this.label(this.endBtn, this.t.end);
    this.el.setAttribute("aria-label", this.t.voiceChat);
  }

  private phaseText(p: Phase): string {
    return { connecting: this.t.connecting, listening: this.t.listening, thinking: this.t.thinking, speaking: this.t.speaking }[p];
  }

  private button(svg: string, onClick: () => void): HTMLButtonElement {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "vc-btn";
    const ic = document.createElement("span");
    ic.className = "vc-ic";
    ic.innerHTML = svg;
    const lbl = document.createElement("span");
    lbl.className = "vc-lbl";
    b.append(ic, lbl);
    b.addEventListener("click", onClick);
    return b;
  }

  private label(b: HTMLButtonElement, text: string): void {
    const lbl = b.querySelector(".vc-lbl");
    if (lbl) lbl.textContent = text;
    b.setAttribute("aria-label", text);
  }
}
