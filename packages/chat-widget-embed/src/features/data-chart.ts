import type { WidgetContext, WidgetFeature } from "../frame/context";
import { defineStrings, type Locale } from "../frame/i18n";
import { enhanceTables } from "../rich-render";
import { CORE } from "../strings";

/**
 * 응답 안 그래프(CR-183, PRD-522, BIZ-224). 답 글의 chart 코드 블록을 막대, 선 그래프(SVG)로 바꾸고
 * 머리줄 「그래프 | 표」 로 같은 값을 CR-131 표(복사, CSV, 엑셀)로도 보여 준다.
 * 블록 형식은 CR-183 3.7, 모양은 T3-3 「응답 안 그래프」 다. 그래프 라이브러리는 쓰지 않는다(T2-1).
 */

const S = defineStrings({
  ko: {
    chart: "그래프",
    table: "표",
    failed: "그래프를 그리지 못했습니다",
    category: "항목",
  },
  en: {
    chart: "Chart",
    table: "Table",
    failed: "Couldn't draw the chart",
    category: "Item",
  },
});

type Texts = (typeof S)["ko"];

/** 위젯 상수(BIZ-224). 색을 여섯 개 두었고, 항목이 더 많으면 막대와 눈금이 읽히지 않는다. */
const MAX_LABELS = 200;
const MAX_SERIES = 6;

const PLOT_HEIGHT = 240;
const PLOT_TOP = 8;
const FONT_SIZE = 11;
const MIN_BAND = { bar: 24, line: 16 } as const;
const BAR_GAP = 2;
const BAR_RADIUS = 4;
/** 항목이 이보다 많으면 선 그래프 점을 마우스를 올린 항목에만 그린다. */
const LINE_DOTS_MAX = 20;
/** 선 끝에 계열 이름을 쓰는 계열 수 상한. 넘으면 범례로만 읽는다. */
const END_LABELS_MAX = 4;
const ROTATED_LABEL_MAX_CHARS = 14;

type PlotType = "bar" | "line";

interface Series {
  name: string;
  data: Array<number | null>;
}

export interface PlotSpec {
  type: PlotType;
  title?: string;
  xLabel?: string;
  yLabel?: string;
  labels: string[];
  series: Series[];
}

/** 형식에 맞지 않으면 null. 그때는 코드 블록을 그대로 둔다. */
export function parsePlot(source: string): PlotSpec | null {
  let raw: unknown;
  try {
    raw = JSON.parse(source);
  } catch {
    return null;
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  if (o.type !== "bar" && o.type !== "line") return null;
  if (!Array.isArray(o.labels) || o.labels.length < 1 || o.labels.length > MAX_LABELS) return null;
  if (!o.labels.every((l) => typeof l === "string" || (typeof l === "number" && Number.isFinite(l)))) return null;
  const labels = o.labels.map((l) => String(l));
  if (!Array.isArray(o.series) || o.series.length < 1 || o.series.length > MAX_SERIES) return null;
  const series: Series[] = [];
  let anyValue = false;
  for (const s of o.series) {
    if (!s || typeof s !== "object") return null;
    const { name, data } = s as Record<string, unknown>;
    if (typeof name !== "string" || !Array.isArray(data) || data.length !== labels.length) return null;
    const values: Array<number | null> = [];
    for (const v of data) {
      if (v === null) {
        values.push(null);
      } else if (typeof v === "number" && Number.isFinite(v)) {
        values.push(v);
        anyValue = true;
      } else {
        return null;
      }
    }
    series.push({ name, data: values });
  }
  if (!anyValue) return null;
  const text = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : undefined);
  return { type: o.type, title: text(o.title), xLabel: text(o.x_label), yLabel: text(o.y_label), labels, series };
}

/** 0 을 포함해 4 ~ 6개 눈금이 나오는 보기 좋은 간격(1, 2, 2.5, 5 에 10 의 거듭제곱을 곱한 값). */
export function niceTicks(min: number, max: number): number[] {
  const lo0 = Math.min(0, min);
  const hi0 = Math.max(0, max) === lo0 ? 1 : Math.max(0, max);
  const span = hi0 - lo0;
  const steps = (s: number) => Math.ceil(hi0 / s - 1e-9) - Math.floor(lo0 / s + 1e-9);
  let mag = 10 ** Math.floor(Math.log10(span / 5));
  let step = 0;
  while (!step) {
    step = [1, 2, 2.5, 5].map((m) => m * mag).find((s) => steps(s) <= 5) ?? 0;
    mag *= 10;
  }
  const lo = Math.floor(lo0 / step + 1e-9) * step;
  const count = steps(step);
  const digits = Math.min(12, Math.max(0, 1 - Math.floor(Math.log10(step))));
  const ticks: number[] = [];
  for (let i = 0; i <= count; i++) ticks.push(Number((lo + i * step).toFixed(digits)));
  return ticks;
}

const UNITS: Record<Locale, Array<[number, string]>> = {
  ko: [[1e12, "조"], [1e8, "억"], [1e4, "만"]],
  en: [[1e12, "T"], [1e9, "B"], [1e6, "M"], [1e3, "K"]],
};

/** 눈금 글. 큰 수는 화면 언어로 줄인다(한국어 1.2만, 영어 12K). */
export function shortNumber(v: number, locale: Locale): string {
  const abs = Math.abs(v);
  for (const [unit, suffix] of UNITS[locale]) {
    if (abs >= unit) return `${trimZeros((v / unit).toFixed(1))}${suffix}`;
  }
  return trimZeros(String(Number(v.toPrecision(6))));
}

function trimZeros(s: string): string {
  return s.includes(".") ? s.replace(/\.?0+$/, "") : s;
}

/** 값 풍선과 표 글. 천 단위 쉼표. */
export function fullNumber(v: number, locale: Locale): string {
  return v.toLocaleString(locale === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 6 });
}

const SVG_NS = "http://www.w3.org/2000/svg";

function svg<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string | number>): SVGElementTagNameMap[K] {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
  return el;
}

function div(className: string, text?: string): HTMLDivElement {
  const el = document.createElement("div");
  el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
}

let measureCanvas: HTMLCanvasElement | null = null;

function textWidth(text: string, font: string): number {
  measureCanvas ??= document.createElement("canvas");
  const g = measureCanvas.getContext("2d");
  if (!g) return text.length * FONT_SIZE * 0.6;
  g.font = font;
  return g.measureText(text).width;
}

function shorten(label: string, max: number): string {
  const chars = Array.from(label);
  return chars.length > max ? `${chars.slice(0, max - 1).join("")}…` : label;
}

/** 0 위 막대는 위쪽만, 0 아래 막대는 아래쪽만 둥글게. */
function barPath(x: number, zeroY: number, valueY: number, w: number): string {
  const r = Math.min(BAR_RADIUS, w / 2, Math.abs(zeroY - valueY));
  const end = valueY + (valueY < zeroY ? r : -r);
  return (
    `M${x},${zeroY}V${end}` +
    `Q${x},${valueY} ${x + r},${valueY}H${x + w - r}` +
    `Q${x + w},${valueY} ${x + w},${end}V${zeroY}Z`
  );
}

/** 그린 뒤 값 풍선이 쓰는 자리 계산. */
interface Frame {
  inner: HTMLDivElement;
  overlay: SVGGElement;
  y: (v: number) => number;
  x: (i: number) => number;
}

interface Hit {
  index: number;
  /** 막대는 그 계열 하나, 선은 null(모든 계열) */
  series: number | null;
  x: number;
  y: number;
}

/** chart 블록 하나. 폭이 바뀌거나 화면 언어가 바뀌면 다시 그린다. */
class Plot {
  readonly root: HTMLDivElement;
  private readonly figure: HTMLDivElement;
  private readonly tableBox: HTMLDivElement;
  private readonly plotTab: HTMLButtonElement;
  private readonly tableTab: HTMLButtonElement;
  private frame: Frame | null = null;
  private tip: HTMLDivElement | null = null;
  private pinned: string | null = null;
  private lastWidth = 0;
  private mode: "plot" | "table" = "plot";

  constructor(
    private readonly spec: PlotSpec,
    private readonly t: Texts,
    private readonly locale: () => Locale,
  ) {
    this.root = div("ach");
    const head = div("ach-head");
    if (spec.title) head.append(div("ach-title", spec.title));
    const tabs = div("ach-tabs");
    this.plotTab = this.tabButton(() => this.show("plot"));
    this.tableTab = this.tabButton(() => this.show("table"));
    tabs.append(this.plotTab, div("ach-tab-sep", "|"), this.tableTab);
    head.append(tabs);
    this.figure = div("ach-figure");
    this.tableBox = div("ach-table");
    this.tableBox.hidden = true;
    this.root.append(head, this.figure, this.tableBox);

    // 고정한 값 풍선은 그래프 바깥을 누르면 닫는다. 위젯은 그림자 영역 안이라 누른 자리를 거슬러 본다.
    const onOutside = (e: Event) => {
      if (!this.root.isConnected) {
        document.removeEventListener("click", onOutside, true);
        return;
      }
      if (this.pinned && !e.composedPath().includes(this.root)) this.hideTip(true);
    };
    document.addEventListener("click", onOutside, true);
  }

  private tabButton(onClick: () => void): HTMLButtonElement {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "ach-tab";
    b.addEventListener("click", onClick);
    return b;
  }

  private show(mode: "plot" | "table"): void {
    if (this.mode === mode) return;
    this.mode = mode;
    this.render(true);
  }

  /** 처음 그리기, 폭이 바뀐 뒤(force 없음), 언어나 보기를 바꾼 뒤(force) 다시 그리기. */
  render(force = false): void {
    this.plotTab.textContent = this.t.chart;
    this.tableTab.textContent = this.t.table;
    this.plotTab.setAttribute("aria-pressed", String(this.mode === "plot"));
    this.tableTab.setAttribute("aria-pressed", String(this.mode === "table"));
    this.figure.hidden = this.mode !== "plot";
    this.tableBox.hidden = this.mode !== "table";
    if (this.mode === "table") {
      if (force || !this.tableBox.firstChild) this.renderTable();
      return;
    }
    const width = Math.floor(this.root.clientWidth) || 320;
    if (!force && width === this.lastWidth) return;
    this.lastWidth = width;
    this.hideTip(true);
    this.figure.replaceChildren();
    this.drawFigure(width);
  }

  private drawFigure(width: number): void {
    const { spec } = this;
    const locale = this.locale();
    const font = `${FONT_SIZE}px ${getComputedStyle(this.root).fontFamily || "sans-serif"}`;
    const n = spec.labels.length;
    const values = spec.series.flatMap((s) => s.data.filter((v): v is number => v !== null));
    const ticks = niceTicks(Math.min(...values), Math.max(...values));
    const yMin = ticks[0];
    const yMax = ticks[ticks.length - 1];
    const tickLabels = ticks.map((v) => shortNumber(v, locale));

    if (spec.series.length >= 2) {
      const legend = div("ach-legend");
      spec.series.forEach((s, i) => {
        const item = div("ach-legend-item");
        item.append(div(`ach-swatch ach-bg-${i + 1}`), document.createTextNode(s.name));
        legend.append(item);
      });
      this.figure.append(legend);
    }
    if (spec.yLabel) this.figure.append(div("ach-axis-name", spec.yLabel));

    const endLabels = spec.type === "line" && spec.series.length <= END_LABELS_MAX;
    const left = Math.ceil(Math.max(...tickLabels.map((l) => textWidth(l, font)))) + 8;
    const right = endLabels
      ? Math.min(120, Math.ceil(Math.max(...spec.series.map((s) => textWidth(s.name, font)))) + 14)
      : 12;
    const band = Math.max(Math.max(width - left - right, 40) / n, MIN_BAND[spec.type]);
    const plotW = band * n;

    const rotate = spec.labels.some((l) => textWidth(l, font) > band - 4);
    const shown = rotate ? spec.labels.map((l) => shorten(l, ROTATED_LABEL_MAX_CHARS)) : spec.labels;
    const skip = rotate ? Math.max(1, Math.ceil((FONT_SIZE + 3) / band)) : 1;
    const bottom = rotate
      ? Math.min(90, Math.ceil(Math.max(...shown.map((l) => textWidth(l, font))) * Math.SQRT1_2) + 16)
      : 22;
    const svgW = left + plotW + right;
    const svgH = PLOT_TOP + PLOT_HEIGHT + bottom;
    const y = (v: number) => PLOT_TOP + PLOT_HEIGHT - ((v - yMin) / (yMax - yMin)) * PLOT_HEIGHT;
    const x = (i: number) => left + band * i + band / 2;
    const zeroY = y(0);

    const scroll = div("ach-scroll");
    const inner = div("ach-inner");
    inner.style.width = `${svgW}px`;
    const root = svg("svg", { width: svgW, height: svgH, viewBox: `0 0 ${svgW} ${svgH}`, role: "img", class: "ach-svg" });
    root.setAttribute("aria-label", spec.title ?? this.t.chart);

    ticks.forEach((tick, k) => {
      const ty = y(tick);
      root.append(svg("line", { x1: left, x2: left + plotW, y1: ty, y2: ty, class: tick === 0 ? "ach-zero" : "ach-grid" }));
      const label = svg("text", { x: left - 6, y: ty, "text-anchor": "end", "dominant-baseline": "middle", class: "ach-tick" });
      label.textContent = tickLabels[k];
      root.append(label);
    });

    const ly = PLOT_TOP + PLOT_HEIGHT + 6;
    for (let i = 0; i < n; i += skip) {
      const cx = x(i);
      const text = svg("text", rotate
        ? { x: cx, y: ly, "text-anchor": "end", "dominant-baseline": "hanging", transform: `rotate(-45 ${cx} ${ly})`, class: "ach-tick" }
        : { x: cx, y: ly, "text-anchor": "middle", "dominant-baseline": "hanging", class: "ach-tick" });
      text.textContent = shown[i];
      root.append(text);
    }

    const marks = svg("g", {});
    const overlay = svg("g", {});
    const hits = svg("g", { class: "ach-hits" });
    root.append(marks, overlay, hits);
    this.frame = { inner, overlay, y, x };

    if (spec.type === "bar") {
      const k = spec.series.length;
      const groupW = band * 0.72;
      const barW = Math.max(1, (groupW - BAR_GAP * (k - 1)) / k);
      spec.series.forEach((s, si) => {
        s.data.forEach((v, i) => {
          const bx = left + band * i + (band - groupW) / 2 + si * (barW + BAR_GAP);
          if (v !== null && v !== 0) {
            marks.append(svg("path", { d: barPath(bx, zeroY, y(v), barW), class: `ach-fill-${si + 1}` }));
          }
          // 맞히는 자리는 막대보다 넓게, 그림 높이 전체로 둔다.
          const hit = svg("rect", { x: bx - BAR_GAP / 2, y: PLOT_TOP, width: barW + BAR_GAP, height: PLOT_HEIGHT });
          this.bindHit(hit, { index: i, series: si, x: bx + barW / 2, y: v === null ? zeroY : Math.min(y(v), zeroY) });
          hits.append(hit);
        });
      });
    } else {
      const allDots = n <= LINE_DOTS_MAX;
      spec.series.forEach((s, si) => {
        let d = "";
        s.data.forEach((v, i) => {
          if (v === null) return;
          d += `${i > 0 && s.data[i - 1] !== null ? "L" : "M"}${x(i)},${y(v)}`;
          const isolated = (i === 0 || s.data[i - 1] === null) && (i === n - 1 || s.data[i + 1] === null);
          if (allDots || isolated) marks.append(this.dot(x(i), y(v), si));
        });
        if (d) marks.insertBefore(svg("path", { d, class: `ach-line ach-stroke-${si + 1}` }), marks.firstChild);
      });
      if (endLabels) this.drawEndLabels(root);
      for (let i = 0; i < n; i++) {
        const hit = svg("rect", { x: left + band * i, y: PLOT_TOP, width: band, height: PLOT_HEIGHT });
        const ys = spec.series.map((s) => s.data[i]).filter((v): v is number => v !== null).map(y);
        this.bindHit(hit, { index: i, series: null, x: x(i), y: ys.length ? Math.min(...ys) : zeroY });
        hits.append(hit);
      }
    }

    inner.append(root);
    scroll.append(inner);
    this.figure.append(scroll);
    if (spec.xLabel) this.figure.append(div("ach-axis-name ach-axis-x", spec.xLabel));
  }

  private dot(cx: number, cy: number, si: number): SVGCircleElement {
    return svg("circle", { cx, cy, r: 4, class: `ach-dot ach-fill-${si + 1}` });
  }

  /** 선 끝 계열 이름. 마지막 값 오른쪽에 두고 겹치면 아래로 민다. 글자색은 계열 색이 아니라 글자색이다. */
  private drawEndLabels(root: SVGSVGElement): void {
    const f = this.frame;
    if (!f) return;
    const items: Array<{ name: string; x: number; y: number }> = [];
    for (const s of this.spec.series) {
      for (let i = s.data.length - 1; i >= 0; i--) {
        const v = s.data[i];
        if (v !== null) {
          items.push({ name: s.name, x: f.x(i) + 8, y: f.y(v) });
          break;
        }
      }
    }
    items.sort((a, b) => a.y - b.y);
    for (let i = 1; i < items.length; i++) {
      items[i].y = Math.max(items[i].y, items[i - 1].y + FONT_SIZE + 2);
    }
    for (const it of items) {
      const text = svg("text", { x: it.x, y: it.y, "dominant-baseline": "middle", class: "ach-end" });
      text.textContent = it.name;
      root.append(text);
    }
  }

  /** 마우스를 올리면 보이고, 누르면 고정, 다시 누르거나 바깥을 누르면 닫는다. */
  private bindHit(hit: SVGRectElement, h: Hit): void {
    const key = `${h.index}:${h.series ?? "*"}`;
    hit.addEventListener("pointerenter", () => {
      if (!this.pinned) this.showTip(h);
    });
    hit.addEventListener("pointerleave", () => {
      if (!this.pinned) this.hideTip(false);
    });
    hit.addEventListener("click", () => {
      const same = this.pinned === key;
      this.hideTip(true);
      if (same) return;
      this.showTip(h);
      this.pinned = key;
    });
  }

  private showTip(h: Hit): void {
    const f = this.frame;
    if (!f) return;
    const { spec } = this;
    const locale = this.locale();
    this.hideTip(false);
    const tip = div("ach-tip");
    tip.append(div("ach-tip-label", spec.labels[h.index]));
    const rows = h.series === null ? spec.series.map((_, i) => i) : [h.series];
    for (const si of rows) {
      const v = spec.series[si].data[h.index];
      const row = div("ach-tip-row");
      row.append(div(`ach-swatch ach-bg-${si + 1}`), div("ach-tip-name", spec.series[si].name),
        div("ach-tip-value", v === null ? "-" : fullNumber(v, locale)));
      tip.append(row);
    }
    if (spec.type === "line") {
      f.overlay.append(svg("line", { x1: h.x, x2: h.x, y1: PLOT_TOP, y2: PLOT_TOP + PLOT_HEIGHT, class: "ach-guide" }));
      if (spec.labels.length > LINE_DOTS_MAX) {
        spec.series.forEach((s, si) => {
          const v = s.data[h.index];
          if (v !== null) f.overlay.append(this.dot(h.x, f.y(v), si));
        });
      }
    }
    f.inner.append(tip);
    const left = Math.max(0, Math.min(h.x - tip.offsetWidth / 2, f.inner.clientWidth - tip.offsetWidth));
    tip.style.left = `${left}px`;
    tip.style.top = `${Math.max(0, h.y - tip.offsetHeight - 8)}px`;
    this.tip = tip;
  }

  private hideTip(unpin: boolean): void {
    if (unpin) this.pinned = null;
    this.tip?.remove();
    this.tip = null;
    this.frame?.overlay.replaceChildren();
  }

  private renderTable(): void {
    const { spec } = this;
    const locale = this.locale();
    const table = document.createElement("table");
    const head = table.createTHead().insertRow();
    for (const h of [spec.xLabel ?? this.t.category, ...spec.series.map((s) => s.name)]) {
      const th = document.createElement("th");
      th.textContent = h;
      head.append(th);
    }
    const body = table.createTBody();
    spec.labels.forEach((label, i) => {
      const tr = body.insertRow();
      tr.insertCell().textContent = label;
      for (const s of spec.series) {
        const v = s.data[i];
        const td = tr.insertCell();
        td.textContent = v === null ? "" : fullNumber(v, locale);
        td.align = "right";
      }
    });
    this.tableBox.replaceChildren(table);
    enhanceTables(this.tableBox, CORE[locale].rich);
  }
}

class DataPlot {
  private readonly t: Texts;
  private readonly plots = new Set<Plot>();
  private observer: ResizeObserver | null = null;

  constructor(private readonly ctx: WidgetContext) {
    this.t = ctx.strings(S);
  }

  install(): void {
    this.ctx.ui.answer.addDecorator({
      id: "data-chart",
      order: 20,
      renderFeatures: ["chart"],
      decorate: (body) => this.decorate(body),
    });
    this.ctx.on("localeChange", () => this.forEachLive((p) => p.render(true)));
    if (typeof ResizeObserver !== "undefined") {
      this.observer = new ResizeObserver(() => this.forEachLive((p) => p.render()));
    }
  }

  private forEachLive(fn: (p: Plot) => void): void {
    for (const p of this.plots) {
      if (p.root.isConnected) {
        fn(p);
      } else {
        this.plots.delete(p);
        this.observer?.unobserve(p.root);
      }
    }
  }

  private decorate(body: HTMLElement): void {
    body.querySelectorAll<HTMLElement>("pre > code.language-chart").forEach((code) => {
      const pre = code.parentElement as HTMLElement;
      if (pre.classList.contains("ach-invalid")) return;
      const spec = parsePlot(code.textContent ?? "");
      if (!spec) {
        pre.classList.add("ach-invalid");
        pre.after(div("ach-failed", this.t.failed));
        return;
      }
      const plot = new Plot(spec, this.t, () => this.ctx.locale());
      pre.replaceWith(plot.root);
      this.plots.add(plot);
      plot.render(true);
      this.observer?.observe(plot.root);
    });
  }
}

/** 계열 색은 순서로 고정한다(T3-3 색표). 색약 검사 도구로 이웃 계열 구분을 확인한 값이다. */
const SERIES_LIGHT = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"];
const SERIES_DARK = ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300"];

const seriesVars = (colors: string[]) => colors.map((c, i) => `--aimbase-series-${i + 1}: ${c};`).join(" ");
const seriesClasses = SERIES_LIGHT.map((_, i) => {
  const n = i + 1;
  return `.ach-fill-${n} { fill: var(--aimbase-series-${n}); } .ach-stroke-${n} { stroke: var(--aimbase-series-${n}); } .ach-bg-${n} { background: var(--aimbase-series-${n}); }`;
}).join("\n");

const CSS = `
:host { ${seriesVars(SERIES_LIGHT)} }
:host([data-theme="dark"]) { ${seriesVars(SERIES_DARK)} }
${seriesClasses}
.ach { margin: 12px 0; width: 100%; }
.ach-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 6px; }
.ach-title { font-size: 14px; font-weight: 600; color: var(--aimbase-fg); flex: 1 1 auto; min-width: 0; }
.ach-tabs { display: inline-flex; align-items: baseline; gap: 4px; margin-left: auto; font-size: 12px; }
.ach-tab { background: none; border: none; padding: 0 2px; cursor: pointer; color: var(--aimbase-muted); font-size: 12px; }
.ach-tab[aria-pressed="true"] { color: var(--aimbase-fg); font-weight: 600; }
.ach-tab-sep { color: var(--aimbase-border-strong); }
.ach-legend { display: flex; flex-wrap: wrap; gap: 4px 12px; margin-bottom: 6px; font-size: 12px; color: var(--aimbase-fg); }
.ach-legend-item { display: inline-flex; align-items: center; gap: 6px; }
.ach-swatch { width: 10px; height: 10px; border-radius: 2px; flex: 0 0 auto; }
.ach-axis-name { font-size: 11px; color: var(--aimbase-muted); margin-bottom: 2px; }
.ach-axis-x { text-align: center; margin: 2px 0 0; }
.ach-scroll { overflow-x: auto; overflow-y: hidden; }
.ach-inner { position: relative; }
.ach-svg { display: block; overflow: visible; }
.ach-svg text { font-size: ${FONT_SIZE}px; }
.ach-tick { fill: var(--aimbase-muted); }
.ach-end { fill: var(--aimbase-fg); }
.ach-grid { stroke: var(--aimbase-border); stroke-width: 1; opacity: 0.7; }
.ach-zero { stroke: var(--aimbase-border-strong); stroke-width: 1; }
.ach-line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.ach-dot { stroke: var(--aimbase-bg); stroke-width: 2; }
.ach-guide { stroke: var(--aimbase-border-strong); stroke-width: 1; }
.ach-hits rect { fill: transparent; cursor: pointer; }
.ach-tip { position: absolute; z-index: 2; pointer-events: none; background: var(--aimbase-bg); color: var(--aimbase-fg);
  border: 1px solid var(--aimbase-border); border-radius: 6px; box-shadow: 0 2px 8px rgba(0,0,0,.12);
  padding: 6px 8px; font-size: 12px; white-space: nowrap; }
.ach-tip-label { font-weight: 600; margin-bottom: 2px; }
.ach-tip-row { display: flex; align-items: center; gap: 6px; }
.ach-tip-name { color: var(--aimbase-muted); }
.ach-tip-value { margin-left: auto; padding-left: 12px; font-variant-numeric: tabular-nums; }
.ach-table td[align="right"] { font-variant-numeric: tabular-nums; }
.ach-failed { font-size: 12px; color: var(--aimbase-muted); margin: -4px 0 8px; }
`;

export const dataChart: WidgetFeature = {
  id: "data-chart",
  css: CSS,
  install: (ctx) => new DataPlot(ctx).install(),
};
