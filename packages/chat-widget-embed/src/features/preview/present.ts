import type { PageSource } from "./pages-view";

export interface PresentOptions {
  layer: HTMLElement;
  src: PageSource;
  start: number;
  pageOf: (n: number, total: number) => string;
  onEscape: (fn: () => boolean) => () => void;
  onEnd: (last: number) => void;
}

/**
 * 지금 장부터 화면 가득 띄운다. 브라우저가 전체 화면을 막으면 페이지를 덮어 같은 동작을 한다.
 * Esc 는 두 방식 모두 위젯 Esc 순서에 먼저 걸어 발표만 끝낸다. 브라우저가 전체 화면을 풀며 Esc 를
 * 삼키는 경우는 fullscreenchange 로 끝낸다. 걸지 않으면 Esc 가 옆 창을 닫고 덮개가 남는다.
 */
export function startPresentation(o: PresentOptions): void {
  const count = o.src.count;
  const overlay = document.createElement("div");
  overlay.className = "pv-present";
  overlay.tabIndex = -1;
  const img = document.createElement("img");
  img.alt = "";
  img.draggable = false;
  const counter = document.createElement("span");
  counter.className = "pv-present-count";
  overlay.append(img, counter);
  o.layer.appendChild(overlay);

  let page = Math.min(Math.max(1, o.start), count);
  let ended = false;
  let fullscreen = false;
  let offEscape: (() => void) | null = null;

  const show = (n: number) => {
    page = Math.min(Math.max(1, n), count);
    counter.textContent = o.pageOf(page, count);
    const want = page;
    o.src.load(want).then(
      (url) => {
        if (page === want) img.src = url;
      },
      () => undefined,
    );
    if (page < count) o.src.load(page + 1).catch(() => undefined);
  };

  const end = () => {
    if (ended) return;
    ended = true;
    document.removeEventListener("fullscreenchange", onFullscreenChange);
    overlay.removeEventListener("keydown", onKey);
    offEscape?.();
    if (fullscreen && document.fullscreenElement) document.exitFullscreen().catch(() => undefined);
    overlay.remove();
    o.onEnd(page);
  };

  const onKey = (e: KeyboardEvent) => {
    switch (e.key) {
      case "ArrowRight":
      case " ":
      case "PageDown":
        show(page + 1);
        break;
      case "ArrowLeft":
      case "PageUp":
        show(page - 1);
        break;
      case "Home":
        show(1);
        break;
      case "End":
        show(count);
        break;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  };

  const onFullscreenChange = () => {
    if (fullscreen && !document.fullscreenElement) end();
  };

  overlay.addEventListener("keydown", onKey);
  overlay.addEventListener("click", (e) => {
    const rect = overlay.getBoundingClientRect();
    if (e.clientX - rect.left >= rect.width / 2) show(page + 1);
    else show(page - 1);
  });

  const cover = () => {
    overlay.classList.add("pv-cover");
    overlay.focus();
  };

  offEscape = o.onEscape(() => {
    end();
    return true;
  });
  show(page);
  if (typeof overlay.requestFullscreen === "function") {
    overlay.requestFullscreen().then(
      () => {
        fullscreen = true;
        document.addEventListener("fullscreenchange", onFullscreenChange);
        overlay.focus();
      },
      cover,
    );
  } else {
    cover();
  }
}
