# @aimbase/chat-widget-embed

Aimbase Chat Widget — **Web Component** 형태로 소비앱 브라우저에 채팅 + 워크플로우 실행 가시화 + RAG 출처 카드를 한 줄 삽입.

## 설치

### CDN / `<script>` 방식 (가장 간단)

```html
<script src="https://your-cdn/aimbase-chat.umd.global.js"></script>
<aimbase-chat
  base-url="https://aimbase.company.com"
  token-endpoint="/my-bff/aimbase-token"
  display="bubble"
></aimbase-chat>
```

### npm 방식 (React/Vue/번들러)

```bash
npm install @aimbase/chat-widget-embed
```

```ts
import { defineAimbaseChat, createWidget } from "@aimbase/chat-widget-embed";

// Web Component 로
defineAimbaseChat();

// 또는 JS API 로 직접
createWidget({
  baseUrl: "https://aimbase.company.com",
  authResolver: async () => {
    const res = await fetch("/my-bff/aimbase-token", { method: "POST" });
    const { data } = await res.json();
    return data; // { token, expires_at, refresh_after, scopes }
  },
  display: "bubble",
});
```

## 3-Tier 아키텍처

```
브라우저 (위젯) → 소비앱 BFF (API Key 보관) → Aimbase (단기 JWT 발급)
```

**브라우저에 테넌트 API Key 노출 금지 원칙**. 소비앱 BFF 가 `/my-bff/aimbase-token` 같은 엔드포인트를 구현해 `X-API-Key` 로 Aimbase `POST /api/v1/sessions/issue-widget-token` 를 호출하고 받은 JWT 를 브라우저에 전달한다.

샘플 BFF: [`sample-bff/server.js`](./sample-bff/server.js) (Node.js 내장 http 만 사용, 외부 의존 0)

## 옵션

| 속성 / 프로퍼티 | 타입 | 기본 | 설명 |
|---|---|---|---|
| `base-url` | string | 필수 | Aimbase 서버 URL |
| `token-endpoint` | string | — | BFF 프록시 경로 (authResolver 를 JS 로 주입하면 생략 가능) |
| `display` | `bubble`\|`inline`\|`panel` | `inline`(CE) / `bubble`(JS API) | 렌더 모드 |
| `session-id` | string | auto UUID | 대화 세션 |
| `rag-source-id` | string | — | RAG 소스 ID (Citations 필요 시) |
| `theme-mode` | `light`\|`dark`\|`auto` | auto | 테마 |

JS API `createWidget(options)` 에는 `contextProvider`, `on.onMessage`, `on.onWorkflowStep`, `on.onApprovalRequired`, `on.onError`, `on.onTokenExpiring` 콜백이 추가 제공된다.

## 서버 요구사항 (CR-058 Sprint 52)

- `POST /api/v1/sessions/issue-widget-token` — 단기 JWT 발급
- `POST /api/v1/chat/completions` (stream) — `done` 이벤트에 `citations`, `rag_used` 포함
- `GET /api/v1/knowledge-sources/{sid}/chunks/{cid}` — citation 원문
- `GET /api/v1/workflows/runs/{runId}/subscribe` — 실행 SSE 구독
- 관리자: `global_config.widget.allowed-origins` CSV 에 소비앱 origin 등록 필수

상세: `docs/guides/aimbase-api-guide.md` § 17 / `docs/guides/aimbase-ops-guide.md` § 2-5.

## 로컬 E2E

```bash
# 1) 위젯 빌드
npm install && npm run build

# 2) 샘플 BFF 실행
cd sample-bff
AIMBASE_URL=http://localhost:8181 \
AIMBASE_API_KEY=plat-... \
AIMBASE_TENANT_ID=tenant_dev \
ALLOWED_ORIGIN=http://localhost:3999 \
PORT=3999 node server.js

# 3) 브라우저에서 http://localhost:3999 접속
```

## 빌드 산출물

- `dist/aimbase-chat.umd.global.js` — `<script>` 로드용 (global: `AimbaseChat`)
- `dist/aimbase-chat.esm.js` — 번들러용 ESM
- `dist/index.d.ts` — TypeScript 타입

UMD ~17KB (minified), ESM ~25KB.

## 제한사항 (Sprint 53 축소 MVP)

- React npm 패키지와 Vue 전용 래퍼는 후속 CR (실제 요청 시 +2MD)
- 음성 입력 / 파일 업로드 / 위젯 내 승인 UI 는 후속 CR-060/061/미정
- 번들 CDN 배포 인프라는 CR-062 로 분리
