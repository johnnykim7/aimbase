import { marked, type Tokens } from "marked";
import type { AnswerView } from "../../frame/context";
import { svgIcon } from "../../icons";
import type { PreviewTarget } from "./model";
import type { PreviewTexts } from "./strings";

const PLAY_ICON = '<polygon points="6 3 20 12 6 21 6 3"/>';
const PANE_ICON = '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M15 3v18"/>';

type BlockLang = "html" | "svg";

interface CodeBlockRef {
  index: number;
  lang: string;
  text: string;
}

/** 코드 블록 순번은 응답 원문 전체에서 0부터 센다(CR-180 4.5). 화면 요소가 글을 나눠도 같은 번호가 나오게 원문에서 센다. */
function codeBlocks(markdown: string): CodeBlockRef[] {
  const out: CodeBlockRef[] = [];
  try {
    marked.walkTokens(marked.lexer(markdown), (token) => {
      if (token.type !== "code") return;
      const code = token as Tokens.Code;
      out.push({ index: out.length, lang: (code.lang ?? "").trim().split(/\s+/)[0].toLowerCase(), text: code.text });
    });
  } catch {
    // 원문을 못 읽으면 화면 순서로 번호를 붙인다.
  }
  return out;
}

function langOf(code: Element): BlockLang | null {
  if (code.classList.contains("language-html")) return "html";
  if (code.classList.contains("language-svg")) return "svg";
  return null;
}

/** 같은 SVG 를 다시 그릴 때마다 새 주소를 만들지 않는다. */
const svgUrls = new Map<string, string>();
export function svgUrl(source: string): string {
  let url = svgUrls.get(source);
  if (!url) {
    url = URL.createObjectURL(new Blob([source], { type: "image/svg+xml" }));
    svgUrls.set(source, url);
  }
  return url;
}

function barButton(label: string, iconPaths: string | null): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "pv-barbtn";
  if (iconPaths) b.innerHTML = svgIcon(iconPaths, 13);
  const span = document.createElement("span");
  span.textContent = label;
  b.appendChild(span);
  return b;
}

export function decorateCodeBlocks(
  body: HTMLElement,
  a: AnswerView,
  t: PreviewTexts,
  open: (target: PreviewTarget) => void,
): void {
  const blocks = codeBlocks(a.text);
  const used = new Set<number>();
  const turnId = a.turnId ?? "";
  body.querySelectorAll("pre > code").forEach((code, domIndex) => {
    const lang = langOf(code);
    const pre = code.parentElement as HTMLElement | null;
    if (!lang || !pre || pre.dataset.pvDone) return;
    pre.dataset.pvDone = "1";
    const source = code.textContent ?? "";
    const ref = blocks.find((b) => !used.has(b.index) && b.lang === lang && b.text.trimEnd() === source.trimEnd());
    if (ref) used.add(ref.index);
    const index = ref ? ref.index : domIndex;
    const target: PreviewTarget = { kind: "block", turnId, index, lang, source: ref ? ref.text : source };
    if (lang === "html") runBar(pre, t, () => open(target));
    else svgFigure(pre, source, t, () => open(target));
  });
}

function runBar(pre: HTMLElement, t: PreviewTexts, run: () => void): void {
  const bar = document.createElement("div");
  bar.className = "pv-codebar";
  const b = barButton(t.run, PLAY_ICON);
  b.addEventListener("click", run);
  bar.appendChild(b);
  pre.before(bar);
}

/** 응답 안 SVG 는 그림으로 먼저 보인다. 그림 자리에서는 SVG 안 스크립트와 바깥 주소가 돌지 않는다. */
function svgFigure(pre: HTMLElement, source: string, t: PreviewTexts, openInPane: () => void): void {
  const fig = document.createElement("div");
  fig.className = "pv-svgblock";
  const bar = document.createElement("div");
  bar.className = "pv-codebar";
  const seg = document.createElement("div");
  seg.className = "pv-seg";
  const pic = barButton(t.picture, null);
  const code = barButton(t.code, null);
  seg.append(pic, code);
  const toPane = barButton(t.openInPane, PANE_ICON);
  toPane.addEventListener("click", openInPane);
  bar.append(seg, toPane);

  const img = document.createElement("img");
  img.className = "pv-svgimg";
  img.alt = "";
  img.src = svgUrl(source);

  const show = (picture: boolean) => {
    img.hidden = !picture;
    pre.hidden = picture;
    pic.classList.toggle("on", picture);
    code.classList.toggle("on", !picture);
  };
  pic.addEventListener("click", () => show(true));
  code.addEventListener("click", () => show(false));

  pre.before(fig);
  fig.append(bar, img, pre);
  show(true);
}
