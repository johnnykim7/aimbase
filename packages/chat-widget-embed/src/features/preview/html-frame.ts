export const SELECTION_MESSAGE = "aimbase-preview-selection";

/** 설정 값이 정책 meta 의 문법을 깨지 못하게 https 출처 모양만 받는다. */
const SOURCE_PATTERN = /^https:\/\/[A-Za-z0-9.-]+(:\d{1,5})?(\/[^\s;,'"<>]*)?$/;

export function safeSources(sources: unknown): string[] {
  if (!Array.isArray(sources)) return [];
  return sources.filter((s): s is string => typeof s === "string" && SOURCE_PATTERN.test(s));
}

export function contentPolicy(sources: string[]): string {
  const from = sources.join(" ");
  const withFrom = (base: string) => (from ? `${base} ${from}` : base);
  return [
    "default-src 'none'",
    withFrom("script-src 'unsafe-inline'"),
    withFrom("style-src 'unsafe-inline'"),
    withFrom("font-src data:"),
    withFrom("img-src data: blob:"),
    "media-src data: blob:",
    "connect-src 'none'",
    "form-action 'none'",
    "base-uri 'none'",
  ].join("; ");
}

/*
 * 격리 틀이라 부모가 틀 안 선택을 읽지 못하므로 틀이 알린다.
 * 키를 뗄 때는 선택이 바뀐 경우만 알려 같은 글이 거듭 가지 않게 한다.
 */
const SELECTION_SCRIPT =
  "<script>(function(){var last='';" +
  "function read(){var s=window.getSelection();return s?String(s):'';}" +
  `function send(t){last=t;parent.postMessage({type:'${SELECTION_MESSAGE}',text:t},'*');}` +
  "window.addEventListener('mouseup',function(){var t=read();if(t.trim())send(t);else last='';},true);" +
  "window.addEventListener('keyup',function(){var t=read();if(!t.trim()){last='';return;}if(t!==last)send(t);},true);" +
  "})();</script>";

/*
 * 정책 meta 와 선택 알림을 원본 앞에 둔다. 원본이 닫히지 않은 태그로 끝나도 둘 다 걸리게 하려는 것이다.
 * 원본의 doctype 은 뒤에 와서 무시되므로 표준 모드는 앞의 doctype 이 정한다.
 */
export function buildSrcdoc(source: string, sources: string[]): string {
  const policy = contentPolicy(sources).replace(/"/g, "&quot;");
  return (
    "<!doctype html><meta charset=\"utf-8\">" +
    `<meta http-equiv="Content-Security-Policy" content="${policy}">` +
    SELECTION_SCRIPT +
    source
  );
}

export const FRAME_SANDBOX = "allow-scripts allow-forms allow-modals";

export function createRunFrame(source: string, sources: string[]): HTMLIFrameElement {
  const frame = document.createElement("iframe");
  frame.setAttribute("sandbox", FRAME_SANDBOX);
  frame.setAttribute("referrerpolicy", "no-referrer");
  frame.className = "pv-frame";
  frame.srcdoc = buildSrcdoc(source, sources);
  return frame;
}

/** 틀 안 코드가 같은 모양을 흉내 내도 받는 것은 사용자가 보내기 전에 보는 칩 글뿐이라 출처만 확인한다. */
export function selectionFromFrame(event: MessageEvent, frame: HTMLIFrameElement | null): string | null {
  if (!frame || event.source !== frame.contentWindow) return null;
  const data = event.data as { type?: unknown; text?: unknown } | null;
  if (!data || data.type !== SELECTION_MESSAGE || typeof data.text !== "string") return null;
  return data.text.trim() ? data.text : null;
}
