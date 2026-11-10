import { defineConfig } from "tsup";

export default defineConfig([
  // ESM build — 번들러 통합(Vite, webpack) + <script type="module"> + UMD 로더의 lazy 타겟.
  // CR-131: splitting 활성화로 mermaid/highlight.js/fflate 는 별도 lazy 청크로 분리.
  //         평소 로드는 core(~70KB)만, [엑셀]/코드/mermaid 사용 시에만 청크 fetch.
  {
    entry: { "aimbase-chat.esm": "src/index.ts" },
    format: ["esm"],
    dts: { entry: "src/index.ts" },
    sourcemap: true,
    clean: true,
    minify: true,
    splitting: true,
    // <script>/UMD 로더로 로드되는 self-hosted 배포판이라 번들러가 없다.
    // 모든 런타임 의존성을 번들에 포함하고, splitting 으로 dynamic import 대상
    // (mermaid/highlight.js/fflate)을 별도 lazy 청크로 분리한다.
    noExternal: [/.*/],
    platform: "browser",
    target: "es2020",
  },
  // UMD-like IIFE 번들 — <script> 직접 삽입용 (전역 window.AimbaseChat).
  // CR-131: 실제 위젯 코드를 담지 않는 얇은 로더. 같은 디렉토리의 aimbase-chat.esm.js 를
  //         dynamic import 하여 코드스플릿/lazy 를 소비앱 무변경으로 유지한다.
  {
    entry: { "aimbase-chat.umd": "src/umd-entry.ts" },
    format: ["iife"],
    globalName: "AimbaseChat",
    sourcemap: true,
    clean: false,
    minify: true,
    splitting: false,
    platform: "browser",
    target: "es2020",
  },
]);
