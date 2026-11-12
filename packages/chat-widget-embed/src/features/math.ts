import type { TokenizerAndRendererExtension, Tokens } from "marked";
import type { WidgetContext, WidgetFeature } from "../frame/context";

/**
 * 응답 안 수식(CR-183, PRD-523). 마크다운 해석 단계에서 수식 자리를 표시 요소로 지키고(글은 원문 그대로),
 * 응답이 그려진 뒤 KaTeX 를 그때 불러 MathML 로 바꾼다. 글꼴 파일을 싣지 않고 브라우저가 그린다(T2-1).
 * 코드 블록과 줄 안 코드는 marked 가 먼저 떼어 가므로 건드리지 않는다. 틀린 수식은 원문 그대로 둔다.
 */

const SLOT = "amath";

/** 구분 기호별 자리. d1 $...$, p \(...\) 는 줄 안, d2 $$...$$, b \[...\] 는 따로 줄. */
type Kind = "d1" | "p" | "d2" | "b";

const DELIMS: Record<Kind, [string, string]> = {
  d1: ["$", "$"],
  p: ["\\(", "\\)"],
  d2: ["$$", "$$"],
  b: ["\\[", "\\]"],
};

const DISPLAY: ReadonlySet<Kind> = new Set<Kind>(["d2", "b"]);

/**
 * 줄 안 자리. 돈 표시와 헷갈리지 않게 $ 하나짜리는 여는 $ 바로 뒤와 닫는 $ 바로 앞이 빈칸이 아니고
 * 닫는 $ 바로 뒤가 숫자가 아닐 때만 수식으로 본다(CR-183 4.2). \$ 는 글자 $ 다.
 */
const INLINE: Array<[Kind, RegExp]> = [
  ["d2", /^\$\$(?!\$)([\s\S]+?)\$\$/],
  ["b", /^\\\[([\s\S]+?)\\\]/],
  ["p", /^\\\(([\s\S]+?)\\\)/],
  ["d1", /^\$(?![\s$])((?:\\[\s\S]|[^\\$\n])*?[^\s\\])\$(?!\d)/],
];

/** 따로 줄 자리. 블록이 그 기호로 시작해 그 기호로 끝나야 한다. */
const BLOCK: Array<[Kind, RegExp]> = [
  ["d2", /^ {0,3}\$\$(?!\$)([\s\S]+?)\$\$[ \t]*(?:\n+|$)/],
  ["b", /^ {0,3}\\\[([\s\S]+?)\\\][ \t]*(?:\n+|$)/],
];

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function token(type: string, kind: Kind, raw: string, tex: string): Tokens.Generic | undefined {
  return tex.trim() ? { type, raw, kind, text: raw.trim() } : undefined;
}

/** 원문을 글로 담은 자리. 소독을 거쳐도 class 와 글은 남는다. */
function slot(tag: "span" | "div", t: Tokens.Generic): string {
  return `<${tag} class="${SLOT} ${SLOT}--${t.kind}">${escapeHtml(String(t.text))}</${tag}>`;
}

export const inlineMath: TokenizerAndRendererExtension = {
  name: "mathInline",
  level: "inline",
  start(src: string) {
    const i = src.search(/\$|\\[[(]/);
    return i < 0 ? undefined : i;
  },
  tokenizer(src: string) {
    for (const [kind, re] of INLINE) {
      const m = re.exec(src);
      if (m) return token("mathInline", kind, m[0], m[1]);
    }
    return undefined;
  },
  renderer(t: Tokens.Generic) {
    return slot("span", t);
  },
};

export const blockMath: TokenizerAndRendererExtension = {
  name: "mathBlock",
  level: "block",
  start(src: string) {
    const i = src.search(/^ {0,3}(?:\$\$|\\\[)/m);
    return i < 0 ? undefined : i;
  },
  tokenizer(src: string) {
    for (const [kind, re] of BLOCK) {
      const m = re.exec(src);
      if (m) return token("mathBlock", kind, m[0], m[1]);
    }
    return undefined;
  },
  renderer(t: Tokens.Generic) {
    return slot("div", t);
  },
};

function kindOf(el: Element): Kind | null {
  for (const k of Object.keys(DELIMS) as Kind[]) {
    if (el.classList.contains(`${SLOT}--${k}`)) return k;
  }
  return null;
}

/** 자리의 원문에서 구분 기호를 떼어 TeX 만 남긴다. */
export function texOf(raw: string, kind: Kind): string {
  const [open, close] = DELIMS[kind];
  const s = raw.trim();
  return s.startsWith(open) && s.endsWith(close) ? s.slice(open.length, s.length - close.length).trim() : s;
}

type Katex = typeof import("katex").default;
let katexLoad: Promise<Katex> | null = null;

function loadKatex(): Promise<Katex> {
  katexLoad ??= import("katex").then((m) => m.default);
  katexLoad.catch(() => {
    katexLoad = null;
  });
  return katexLoad;
}

async function renderSlots(slots: HTMLElement[]): Promise<void> {
  let katex: Katex;
  try {
    katex = await loadKatex();
  } catch {
    return; // 불러오지 못하면 원문 그대로 둔다
  }
  for (const el of slots) {
    const kind = kindOf(el);
    if (!kind || !el.isConnected) continue;
    try {
      el.innerHTML = katex.renderToString(texOf(el.textContent ?? "", kind), {
        displayMode: DISPLAY.has(kind),
        output: "mathml",
        throwOnError: true,
        trust: false,
        strict: "ignore",
        maxExpand: 1000,
      });
      el.classList.add(`${SLOT}-done`);
    } catch {
      el.classList.add(`${SLOT}-raw`);
    }
  }
}

function install(ctx: WidgetContext): void {
  ctx.ui.answer.addMarkdownExtension({ id: "math-block", extension: blockMath });
  ctx.ui.answer.addMarkdownExtension({ id: "math-inline", extension: inlineMath });
  ctx.ui.answer.addDecorator({
    id: "math",
    order: 30,
    renderFeatures: ["math"],
    decorate: (body) => {
      const slots = Array.from(body.querySelectorAll<HTMLElement>(`.${SLOT}:not(.${SLOT}-done):not(.${SLOT}-raw)`));
      if (slots.length) void renderSlots(slots);
    },
  });
}

const CSS = `
.${SLOT}--d2, .${SLOT}--b { display: block; text-align: center; overflow-x: auto; overflow-y: hidden; margin: 8px 0; }
.${SLOT}-raw { white-space: pre-wrap; }
.${SLOT} math { font-size: 1.1em; }
`;

export const math: WidgetFeature = {
  id: "math",
  css: CSS,
  install,
};
