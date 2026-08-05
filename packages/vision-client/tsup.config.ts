import { defineConfig } from "tsup";

// 의존성 0 인 단일 모듈이라 번들 구성이 단순하다.
// 소비앱은 번들러(Vite/webpack)로 가져다 쓰는 것을 기본으로 하고,
// Node 스크립트에서 쓰는 경우를 위해 CJS 도 함께 낸다.
export default defineConfig({
  entry: { index: "src/index.ts" },
  format: ["esm", "cjs"],
  dts: true,
  sourcemap: true,
  clean: true,
  minify: false, // SDK 는 소비앱 번들러가 다시 minify 한다 — 여기선 가독성 우선
  platform: "browser",
  target: "es2020",
});
