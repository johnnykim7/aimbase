import { defineConfig } from "tsup";

export default defineConfig([
  // ESM build — 번들러 통합 (Vite, webpack) 용
  {
    entry: { "aimbase-chat.esm": "src/index.ts" },
    format: ["esm"],
    dts: { entry: "src/index.ts" },
    sourcemap: true,
    clean: true,
    minify: false,
    platform: "browser",
    target: "es2020",
  },
  // UMD-like IIFE 번들 — <script> 직접 삽입용 (브라우저 전역 window.AimbaseChat 노출)
  {
    entry: { "aimbase-chat.umd": "src/index.ts" },
    format: ["iife"],
    globalName: "AimbaseChat",
    sourcemap: true,
    clean: false,
    minify: true,
    platform: "browser",
    target: "es2020",
  },
]);
