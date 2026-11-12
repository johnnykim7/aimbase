/**
 * CR-186: 응답 마크다운에서 읽을 글을 만들고 소리 요청 조각으로 나눈다(BIZ-250, BIZ-251).
 * 답 읽어 주기와 음성 대화가 같이 쓴다. 화면 틀에 기대지 않는 순수 함수만 둔다.
 */

/** 코드 묶음과 표 대신 읽는 한 줄. 화면 언어에 맞춰 기능 파일이 넘긴다. */
export interface SpeechPhrases {
  code: string;
  table: string;
}

const FENCE = /^\s{0,3}(`{3,}|~{3,})/;
const TABLE_ROW = /^\s*\|.*\|\s*$/;
const TABLE_RULE = /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/;
const HR = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const TERMINATORS = ".?!…。";

/** 응답 마크다운 전체를 읽을 글로 바꾼다. 줄마다 한 문장 끝으로 본다. 읽을 것이 없으면 빈 글. */
export function toSpeechText(markdown: string, phrases: SpeechPhrases): string {
  const out: string[] = [];
  let lastBlock: "code" | "table" | null = null;
  let fence: string | null = null;

  const pushBlock = (kind: "code" | "table") => {
    if (lastBlock === kind) return;
    out.push(kind === "code" ? phrases.code : phrases.table);
    lastBlock = kind;
  };

  for (const line of markdown.split("\n")) {
    const f = FENCE.exec(line);
    if (fence) {
      if (f && f[1][0] === fence[0] && f[1].length >= fence.length) fence = null;
      continue;
    }
    if (f) {
      fence = f[1];
      pushBlock("code");
      continue;
    }
    if (HR.test(line)) continue;
    if (isTableLine(line)) {
      pushBlock("table");
      continue;
    }
    const text = stripInline(line);
    if (!text) continue;
    out.push(text);
    lastBlock = null;
  }
  return out.join("\n");
}

function isTableLine(line: string): boolean {
  return line.includes("|") && (TABLE_ROW.test(line) || TABLE_RULE.test(line));
}

/** 한 줄의 마크다운 기호를 빼고 보이는 글만 남긴다. */
export function stripInline(line: string): string {
  let s = line;
  s = s.replace(/^\s{0,3}#{1,6}\s+/, "");
  s = s.replace(/^\s*(>\s?)+/, "");
  s = s.replace(/^\s*([-*+]|\d{1,9}[.)])\s+(\[[ xX]\]\s+)?/, "");
  s = s.replace(/!\[[^\]]*\]\([^)]*\)/g, "");
  s = s.replace(/\[([^\]]+)\]\([^)]*\)/g, "$1");
  s = s.replace(/<(https?:[^>\s]+)>/g, "");
  s = s.replace(/`+([^`]+?)`+/g, "$1");
  s = s.replace(/~~(.+?)~~/g, "$1");
  s = s.replace(/(\*\*|__)(.+?)\1/g, "$2");
  s = s.replace(/\*(\S(?:.*?\S)?)\*/g, "$1");
  s = s.replace(/(^|[^\w])_(\S(?:.*?\S)?)_(?=[^\w]|$)/g, "$1$2");
  s = s.replace(/<\/?[a-zA-Z][^>]*>/g, " ");
  s = s.replace(/\*\*|\*/g, "");
  return s.replace(/\s+/g, " ").trim();
}

/** 문장 끝(마침표, 물음표, 느낌표, 줄바꿈)에서 나눈다. 3.5 같은 소수점은 끝으로 보지 않는다. */
export function splitSentences(text: string): string[] {
  const sentences: string[] = [];
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "\n") {
      push(text.slice(start, i));
      start = i + 1;
      continue;
    }
    if (!TERMINATORS.includes(ch)) continue;
    let end = i + 1;
    while (end < text.length && (TERMINATORS.includes(text[end]) || "\"')]」』”’".includes(text[end]))) end++;
    if (end < text.length && !/\s/.test(text[end])) continue;
    push(text.slice(start, end));
    start = end;
    i = end - 1;
  }
  push(text.slice(start));
  return sentences;

  function push(s: string) {
    const t = s.trim();
    if (t) sentences.push(t);
  }
}

/** 상한보다 긴 한 문장을 쉼표, 띄어쓰기 자리에서 자른다. 그런 자리가 없으면 상한에서 자른다. */
export function splitLong(sentence: string, maxChars: number): string[] {
  const parts: string[] = [];
  let rest = sentence.trim();
  while (rest.length > maxChars) {
    const window = rest.slice(0, maxChars);
    let cut = Math.max(window.lastIndexOf(","), window.lastIndexOf("，"), window.lastIndexOf("、"));
    if (cut > 0) cut += 1;
    else cut = window.lastIndexOf(" ");
    if (cut <= 0) cut = maxChars;
    parts.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) parts.push(rest);
  return parts;
}

/**
 * 읽을 글을 소리 요청 조각으로 나눈다. 첫 조각은 빨리 시작하려고 한 문장이고, 그 뒤는 상한을 넘지 않게 문장을 모은다.
 *
 * @param startsAnswer 이 글이 응답의 맨 앞이면 true. 이어 붙는 글이면 첫 문장 규칙을 쓰지 않는다
 */
export function chunkSpeech(text: string, maxChars: number, startsAnswer = true): string[] {
  const pieces = splitSentences(text).flatMap((s) => splitLong(s, maxChars));
  const chunks: string[] = [];
  let current = "";
  pieces.forEach((p, i) => {
    if (startsAnswer && i === 0) {
      chunks.push(p);
      return;
    }
    if (current && current.length + 1 + p.length > maxChars) {
      chunks.push(current);
      current = "";
    }
    current = current ? `${current} ${p}` : p;
  });
  if (current) chunks.push(current);
  return chunks;
}

/**
 * 응답이 오는 동안 끝난 문장부터 조각을 낸다(음성 대화). 코드 묶음이나 표가 열려 있으면 닫힐 때까지 그 뒤를 내지 않는다.
 */
export class StreamingSpeech {
  private consumed = 0;
  private started = false;

  constructor(private readonly maxChars: number, private readonly phrases: SpeechPhrases) {}

  /** 지금까지 온 응답 마크다운 전체를 넣는다. 새로 읽을 수 있게 된 조각을 돌려준다. */
  push(markdownSoFar: string): string[] {
    const speech = toSpeechText(stablePrefix(markdownSoFar), this.phrases);
    const rest = speech.slice(this.consumed);
    const end = lastSentenceEnd(rest);
    if (end <= 0) return [];
    return this.take(rest.slice(0, end), end);
  }

  /** 응답이 끝났을 때 남은 것을 모두 낸다. */
  finish(markdownFull: string): string[] {
    const rest = toSpeechText(markdownFull, this.phrases).slice(this.consumed);
    return this.take(rest, rest.length);
  }

  private take(text: string, advance: number): string[] {
    this.consumed += advance;
    if (!text.trim()) return [];
    const chunks = chunkSpeech(text, this.maxChars, !this.started);
    if (chunks.length) this.started = true;
    return chunks;
  }
}

/** 지금 확정해 읽어도 되는 앞부분. 열린 코드 묶음, 끝에 걸린 표, 기호가 덜 닫힌 마지막 줄은 뺀다. */
export function stablePrefix(markdown: string): string {
  const lines = markdown.split("\n");
  const partial = lines.pop() ?? "";
  let fence: string | null = null;
  let fenceStart = -1;
  lines.forEach((line, i) => {
    const f = FENCE.exec(line);
    if (fence) {
      if (f && f[1][0] === fence[0] && f[1].length >= fence.length) fence = null;
    } else if (f) {
      fence = f[1];
      fenceStart = i;
    }
  });
  if (fence) return lines.slice(0, fenceStart).join("\n");

  const partialIsTable = partial.trimStart().startsWith("|");
  let tableStart = lines.length;
  while (tableStart > 0) {
    const l = lines[tableStart - 1];
    if (isTableLine(l)) tableStart--;
    else break;
  }
  const endsInTable = tableStart < lines.length;
  if (endsInTable && (partial.trim() === "" || partialIsTable)) {
    return lines.slice(0, tableStart).join("\n");
  }

  const complete = lines.join("\n");
  const head = partial.trimStart();
  if (!head || head.startsWith("`") || head.startsWith("~") || partialIsTable || !balanced(partial)) {
    return lines.length ? complete + "\n" : "";
  }
  const end = lastSentenceEnd(partial);
  const tail = end > 0 ? partial.slice(0, end) : "";
  return lines.length ? `${complete}\n${tail}` : tail;
}

function balanced(s: string): boolean {
  const count = (re: RegExp) => (s.match(re) ?? []).length;
  return count(/`/g) % 2 === 0
    && count(/\[/g) === count(/\]/g)
    && count(/\(/g) === count(/\)/g)
    && count(/\*\*/g) % 2 === 0;
}

/** 마지막 문장 끝 바로 뒤 위치. 없으면 0. 줄바꿈도 문장 끝이다. */
function lastSentenceEnd(text: string): number {
  for (let i = text.length - 1; i >= 0; i--) {
    const ch = text[i];
    if (ch === "\n") return i + 1;
    if (!TERMINATORS.includes(ch)) continue;
    const next = text[i + 1];
    if (next === undefined || /\s/.test(next)) return i + 1;
  }
  return 0;
}
