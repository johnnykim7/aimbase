import { icon } from "../../icons";
import type { PreviewTexts } from "./strings";

export interface PageSource {
  count: number;
  /** 첫 쪽 그림의 픽셀 크기. 그림이 오기 전 자리 크기를 잡는다 */
  width: number;
  height: number;
  load(page: number): Promise<string>;
}

export interface PagesHandle {
  current(): number;
  go(page: number): void;
  dispose(): void;
}

function clamp(n: number, count: number): number {
  return Math.min(Math.max(1, Math.round(n)), Math.max(1, count));
}

function ratio(src: PageSource): string {
  return src.width > 0 && src.height > 0 ? `${src.width} / ${src.height}` : "4 / 3";
}

/** 슬라이드: 한 장씩 보이고 다음 장을 미리 받아 넘김이 끊기지 않게 한다. */
export function renderSlides(
  body: HTMLElement,
  src: PageSource,
  start: number,
  t: PreviewTexts,
  onPage: (page: number) => void,
): PagesHandle {
  const wrap = document.createElement("div");
  wrap.className = "pv-slides";
  wrap.tabIndex = 0;
  const stage = document.createElement("div");
  stage.className = "pv-stage";
  const img = document.createElement("img");
  img.alt = "";
  img.draggable = false;
  stage.appendChild(img);

  const nav = document.createElement("div");
  nav.className = "pv-nav";
  const prev = navButton(icon("cleft", 16), t.prev);
  const next = navButton(icon("cright", 16), t.next);
  const label = document.createElement("span");
  label.className = "pv-count";
  nav.append(prev, label, next);
  wrap.append(stage, nav);
  body.appendChild(wrap);

  let page = clamp(start, src.count);
  const show = (n: number) => {
    page = clamp(n, src.count);
    label.textContent = t.pageOf(page, src.count);
    prev.disabled = page <= 1;
    next.disabled = page >= src.count;
    stage.classList.remove("pv-err");
    stage.classList.add("pv-loading");
    const want = page;
    src.load(want).then(
      (url) => {
        if (page !== want) return;
        img.src = url;
        stage.classList.remove("pv-loading");
      },
      () => {
        if (page !== want) return;
        stage.classList.remove("pv-loading");
        stage.classList.add("pv-err");
        stage.setAttribute("data-msg", t.failed);
      },
    );
    if (page < src.count) src.load(page + 1).catch(() => undefined);
    onPage(page);
  };

  prev.addEventListener("click", () => show(page - 1));
  next.addEventListener("click", () => show(page + 1));
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "ArrowLeft") show(page - 1);
    else if (e.key === "ArrowRight") show(page + 1);
    else return;
    e.preventDefault();
  };
  wrap.addEventListener("keydown", onKey);
  show(page);

  return {
    current: () => page,
    go: show,
    dispose: () => wrap.removeEventListener("keydown", onKey),
  };
}

/** 문서: 쪽을 위아래로 잇고, 보이는 쪽만 그림을 받는다. */
export function renderScroll(
  body: HTMLElement,
  src: PageSource,
  t: PreviewTexts,
  onPage: (page: number) => void,
): PagesHandle {
  const scroller = document.createElement("div");
  scroller.className = "pv-pages";
  const slots: HTMLElement[] = [];
  for (let n = 1; n <= src.count; n++) {
    const slot = document.createElement("div");
    slot.className = "pv-page pv-loading";
    slot.style.aspectRatio = ratio(src);
    slot.dataset.page = String(n);
    slots.push(slot);
    scroller.appendChild(slot);
  }
  body.appendChild(scroller);

  const load = (slot: HTMLElement) => {
    if (slot.dataset.loaded) return;
    slot.dataset.loaded = "1";
    const n = Number(slot.dataset.page);
    src.load(n).then(
      (url) => {
        const img = document.createElement("img");
        img.alt = "";
        img.draggable = false;
        img.src = url;
        slot.appendChild(img);
        slot.classList.remove("pv-loading");
      },
      () => {
        slot.classList.remove("pv-loading");
        slot.classList.add("pv-err");
        slot.setAttribute("data-msg", t.failed);
      },
    );
  };
  const io = new IntersectionObserver(
    (entries) => entries.forEach((e) => e.isIntersecting && load(e.target as HTMLElement)),
    { root: scroller, rootMargin: "400px 0px" },
  );
  slots.forEach((s) => io.observe(s));

  let page = 1;
  let ticking = false;
  const track = () => {
    ticking = false;
    const line = scroller.scrollTop + scroller.clientHeight * 0.3;
    let n = 1;
    for (const s of slots) {
      if (s.offsetTop <= line) n = Number(s.dataset.page);
      else break;
    }
    if (n !== page) {
      page = n;
      onPage(page);
    }
  };
  const onScroll = () => {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(track);
  };
  scroller.addEventListener("scroll", onScroll, { passive: true });
  onPage(page);

  return {
    current: () => page,
    go: (n: number) => {
      const slot = slots[clamp(n, src.count) - 1];
      if (slot) scroller.scrollTop = slot.offsetTop;
    },
    dispose: () => {
      io.disconnect();
      scroller.removeEventListener("scroll", onScroll);
    },
  };
}

function navButton(svg: string, label: string): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "pv-navbtn";
  b.innerHTML = svg;
  b.setAttribute("aria-label", label);
  b.title = label;
  return b;
}
