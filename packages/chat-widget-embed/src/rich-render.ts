/**
 * CR-131: 위젯 리치 렌더링 (claude.ai 대화창 수준).
 *
 * 어시스턴트 응답 마크다운을 → sanitize → DOM 으로 렌더하고, 후처리로
 *  - 표: [CSV] [엑셀] [복사] 다운로드 버튼 삽입 (클라이언트 변환)
 *  - 코드블록: 문법 하이라이트(highlight.js lazy) + 복사 버튼
 *  - mermaid: ```mermaid``` 블록 → 다이어그램(mermaid lazy)
 * 을 붙인다.
 *
 * 설계:
 *  - 스트리밍 중엔 widget.ts 가 textContent 로 원문 출력(부분 마크다운 깨짐 방지),
 *    done 시점에 renderRichInto() 로 1회 최종 교체.
 *  - marked(GFM) → DOMPurify.sanitize → innerHTML. LLM 출력의 <script>/onerror 차단.
 *  - highlight.js / mermaid / fflate 는 lazy dynamic import — 평소 번들 영향 없음.
 */

import DOMPurify from "dompurify";
import { marked } from "marked";
import { tableToXlsxBlob } from "./xlsx-writer";

let markedConfigured = false;
function configureMarked(): void {
  if (markedConfigured) return;
  marked.setOptions({
    gfm: true,
    breaks: true, // 채팅 응답은 단일 개행도 <br> 로 (claude.ai 유사)
  });
  markedConfigured = true;
}

// mermaid 는 페이지에서 1회만 초기화.
let mermaidInitPromise: Promise<typeof import("mermaid").default> | null = null;
async function getMermaid(theme: "light" | "dark"): Promise<typeof import("mermaid").default> {
  if (!mermaidInitPromise) {
    mermaidInitPromise = import("mermaid").then((mod) => {
      const m = mod.default;
      m.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: theme === "dark" ? "dark" : "default",
        fontFamily: "inherit",
      });
      return m;
    });
  }
  return mermaidInitPromise;
}

let mermaidSeq = 0;

/**
 * DOMPurify 설정: 코드/표/헤딩/링크 등 마크다운 산출 태그를 허용하되
 * 스크립트·이벤트 핸들러·위험 프로토콜은 차단. 링크는 target=_blank + rel 강제.
 */
function sanitize(rawHtml: string): string {
  return DOMPurify.sanitize(rawHtml, {
    ALLOWED_TAGS: [
      "p", "br", "hr", "div", "span",
      "strong", "b", "em", "i", "del", "s", "u", "mark", "sub", "sup",
      "h1", "h2", "h3", "h4", "h5", "h6",
      "ul", "ol", "li",
      "blockquote",
      "a",
      "code", "pre",
      "table", "thead", "tbody", "tr", "th", "td",
      "img",
    ],
    ALLOWED_ATTR: ["href", "title", "class", "src", "alt", "align", "colspan", "rowspan"],
    ALLOW_DATA_ATTR: false,
    // 상대·http(s)·mailto·data(image) 만. javascript: 차단.
    ALLOWED_URI_REGEXP:
      /^(?:(?:https?|mailto|tel):|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$)|data:image\/)/i,
  });
}

/** 링크에 target=_blank + rel 보안 속성 강제 (sanitize 후 DOM 조작). */
function hardenLinks(container: HTMLElement): void {
  container.querySelectorAll("a[href]").forEach((a) => {
    a.setAttribute("target", "_blank");
    a.setAttribute("rel", "noopener noreferrer nofollow");
  });
}

/** <table> 에서 2차원 셀 배열 추출 (헤더 행 포함). */
function extractTable(table: HTMLTableElement): string[][] {
  const rows: string[][] = [];
  table.querySelectorAll("tr").forEach((tr) => {
    const cells: string[] = [];
    tr.querySelectorAll("th, td").forEach((cell) => {
      cells.push((cell.textContent ?? "").trim());
    });
    if (cells.length) rows.push(cells);
  });
  return rows;
}

/** 문자열을 CSV 필드로 이스케이프 (RFC 4180). */
function csvField(s: string): string {
  if (/[",\r\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

function tableToCsv(rows: string[][]): string {
  return rows.map((r) => r.map(csvField).join(",")).join("\r\n");
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    a.remove();
    URL.revokeObjectURL(url);
  }, 0);
}

let tableSeq = 0;

/** 표마다 [CSV] [엑셀] [복사] 툴바를 위에 삽입. */
function enhanceTables(container: HTMLElement): void {
  container.querySelectorAll("table").forEach((table) => {
    const rows = extractTable(table as HTMLTableElement);
    if (!rows.length) return;

    const seq = ++tableSeq;
    const wrap = document.createElement("div");
    wrap.className = "rich-table-wrap";
    const toolbar = document.createElement("div");
    toolbar.className = "rich-table-toolbar";

    const mkBtn = (label: string, title: string): HTMLButtonElement => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "rich-tbl-btn";
      b.textContent = label;
      b.title = title;
      return b;
    };

    const csvBtn = mkBtn("CSV", "CSV 다운로드");
    csvBtn.addEventListener("click", () => {
      // Excel 한글 깨짐 방지 UTF-8 BOM.
      const blob = new Blob(["﻿" + tableToCsv(rows)], { type: "text/csv;charset=utf-8" });
      downloadBlob(blob, `table-${seq}.csv`);
    });

    const xlsxBtn = mkBtn("엑셀", "엑셀(.xlsx) 다운로드");
    xlsxBtn.addEventListener("click", async () => {
      xlsxBtn.disabled = true;
      const prev = xlsxBtn.textContent;
      xlsxBtn.textContent = "…";
      try {
        const blob = await tableToXlsxBlob(rows, `Sheet${seq}`);
        downloadBlob(blob, `table-${seq}.xlsx`);
      } catch {
        xlsxBtn.textContent = "실패";
        setTimeout(() => { xlsxBtn.textContent = prev; }, 1500);
        xlsxBtn.disabled = false;
        return;
      }
      xlsxBtn.textContent = prev;
      xlsxBtn.disabled = false;
    });

    const copyBtn = mkBtn("복사", "표를 클립보드에 복사 (탭 구분)");
    copyBtn.addEventListener("click", async () => {
      const tsv = rows.map((r) => r.join("\t")).join("\n");
      try {
        await navigator.clipboard.writeText(tsv);
        flashCopied(copyBtn, "복사");
      } catch {
        /* clipboard 미허용 환경 — 무시 */
      }
    });

    toolbar.appendChild(csvBtn);
    toolbar.appendChild(xlsxBtn);
    toolbar.appendChild(copyBtn);

    // table 을 wrap 으로 감싸고 toolbar 를 위에 배치.
    table.parentNode?.insertBefore(wrap, table);
    wrap.appendChild(toolbar);
    const scroll = document.createElement("div");
    scroll.className = "rich-table-scroll";
    wrap.appendChild(scroll);
    scroll.appendChild(table);
  });
}

function flashCopied(btn: HTMLButtonElement, restore: string): void {
  const prev = btn.textContent;
  btn.textContent = "✓";
  setTimeout(() => { btn.textContent = restore ?? prev; }, 1200);
}

/** 코드블록: 복사 버튼 + highlight.js 하이라이트(lazy). mermaid 는 제외. */
async function enhanceCodeBlocks(container: HTMLElement, theme: "light" | "dark"): Promise<void> {
  const pres = Array.from(container.querySelectorAll("pre > code")) as HTMLElement[];
  const codeBlocks = pres.filter((code) => !isMermaidCode(code));
  if (!codeBlocks.length) return;

  // 복사 버튼은 즉시, 하이라이트는 lazy.
  for (const code of codeBlocks) {
    const pre = code.parentElement as HTMLElement;
    pre.classList.add("rich-code");
    const copyBtn = document.createElement("button");
    copyBtn.type = "button";
    copyBtn.className = "rich-code-copy";
    copyBtn.textContent = "복사";
    copyBtn.title = "코드 복사";
    copyBtn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(code.textContent ?? "");
        flashCopied(copyBtn, "복사");
      } catch {
        /* ignore */
      }
    });
    pre.appendChild(copyBtn);
  }

  try {
    const hljsMod = await import("highlight.js/lib/common");
    const hljs = hljsMod.default;
    for (const code of codeBlocks) {
      // language-xxx 클래스에서 언어 추출.
      const langClass = Array.from(code.classList).find((c) => c.startsWith("language-"));
      const lang = langClass?.slice("language-".length);
      const src = code.textContent ?? "";
      try {
        const result = lang && hljs.getLanguage(lang)
          ? hljs.highlight(src, { language: lang })
          : hljs.highlightAuto(src);
        code.innerHTML = result.value;
        code.classList.add("hljs", `hljs-theme-${theme}`);
      } catch {
        /* 하이라이트 실패 시 원문 유지 */
      }
    }
  } catch {
    /* highlight.js 로드 실패 — 복사 버튼만으로 진행 */
  }
}

function isMermaidCode(code: HTMLElement): boolean {
  return code.classList.contains("language-mermaid");
}

/** ```mermaid``` 블록을 다이어그램으로 렌더 (lazy). 실패 시 원본 코드 유지. */
async function enhanceMermaid(container: HTMLElement, theme: "light" | "dark"): Promise<void> {
  const blocks = Array.from(container.querySelectorAll("pre > code.language-mermaid")) as HTMLElement[];
  if (!blocks.length) return;

  let mermaid: typeof import("mermaid").default;
  try {
    mermaid = await getMermaid(theme);
  } catch {
    return; // 로드 실패 — 원본 코드블록 그대로 노출
  }

  for (const code of blocks) {
    const pre = code.parentElement as HTMLElement;
    const source = code.textContent ?? "";
    const id = `aimbase-mermaid-${++mermaidSeq}`;
    try {
      const { svg } = await mermaid.render(id, source);
      const holder = document.createElement("div");
      holder.className = "rich-mermaid";
      holder.innerHTML = svg; // mermaid securityLevel:strict 로 자체 sanitize
      pre.replaceWith(holder);
    } catch {
      // 구문 오류 등 — 원본 코드블록에 표식만 남기고 유지.
      pre.classList.add("rich-mermaid-error");
    }
  }
}

/**
 * 마크다운 문자열을 target 요소 안에 리치 렌더링.
 * done 시점에 1회 호출한다.
 */
export function renderRichInto(
  target: HTMLElement,
  markdown: string,
  opts?: { theme?: "light" | "dark" },
): void {
  const theme = opts?.theme ?? "light";
  configureMarked();

  let html: string;
  try {
    html = marked.parse(markdown, { async: false }) as string;
  } catch {
    // 파싱 실패 시 원문 유지 (textContent).
    target.textContent = markdown;
    return;
  }

  const safe = sanitize(html);
  target.innerHTML = safe;
  target.classList.add("rich");

  hardenLinks(target);
  enhanceTables(target);
  // 코드/mermaid 는 lazy — 비동기로 순차 강화 (렌더는 이미 끝났으므로 UX 영향 없음).
  void enhanceCodeBlocks(target, theme);
  void enhanceMermaid(target, theme);
}
