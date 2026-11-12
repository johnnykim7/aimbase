/**
 * CR-131: UMD(<script>) 진입점 = 얇은 로더.
 *
 * 소비앱은 기존 계약대로 `<script src=".../aimbase-chat.umd.global.js"></script>` 로 로드한다.
 * 이 파일은 IIFE 로 번들되지만 실제 위젯 코드는 포함하지 않고, 같은 디렉토리의
 * 코드스플릿된 ESM 엔트리(aimbase-chat.esm.js)를 dynamic import 로 불러온다.
 *
 * 이유: IIFE 는 단일 파일이라 dynamic import 를 인라인해버려 mermaid(~2.8MB) 등이
 * 통째로 들어간다(첫 로드 3.5MB). ESM 엔트리는 splitting 이 되므로 mermaid/highlight.js/
 * fflate 가 별도 lazy 청크로 분리되고, 평소 로드는 core(~70KB)만 된다.
 *
 * 클래식 스크립트(비 module)도 모던 브라우저에서 dynamic import() 를 지원하므로
 * 소비앱 임베드 코드는 무변경으로 lazy 코드스플릿 효과를 얻는다.
 */

// document.currentScript 는 실행 시점(모듈 로드 중)에만 유효하므로 즉시 캡처.
const selfScript =
  typeof document !== "undefined"
    ? (document.currentScript as HTMLScriptElement | null)
    : null;
const selfSrc = selfScript?.src ?? "";

/** 자기 스크립트 URL 기준으로 ESM 엔트리 절대 URL 계산. */
function resolveEsmUrl(): string {
  try {
    // .../aimbase-chat.umd.global.js → .../aimbase-chat.esm.js
    return new URL("./aimbase-chat.esm.js", selfSrc).href;
  } catch {
    return "./aimbase-chat.esm.js";
  }
}

type WidgetModule = typeof import("./index");

let modPromise: Promise<WidgetModule> | null = null;
function loadModule(): Promise<WidgetModule> {
  if (!modPromise) {
    // 변수 URL 이라 번들러가 인라인하지 못하고 런타임 fetch 한다(의도된 lazy).
    modPromise = import(/* @vite-ignore */ resolveEsmUrl()) as Promise<WidgetModule>;
  }
  return modPromise;
}

// 커스텀 엘리먼트 자동 등록은 ESM 엔트리 로드 시 수행됨. 여기서 즉시 트리거.
void loadModule().catch(() => {
  // 로드 실패 시 조용히 무시 — init() 호출 시 다시 시도된다.
});

/**
 * 전역 편의 API. `<script>` 로드 후 `window.AimbaseChat.init(opts)` 로 사용.
 * ESM 엔트리를 lazy 로드한 뒤 실제 createWidget 을 호출하므로 Promise 를 반환한다.
 */
export async function init(
  options: import("./types").WidgetOptions,
): Promise<import("./types").WidgetHandle> {
  const mod = await loadModule();
  return mod.createWidget(options);
}

/** 커스텀 엘리먼트를 수동 등록해야 하는 경우. */
export async function defineAimbaseChat(): Promise<void> {
  const mod = await loadModule();
  mod.defineAimbaseChat();
}
