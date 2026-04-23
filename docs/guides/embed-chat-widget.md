# Aimbase Chat Widget — 소비앱 통합 가이드

> **대상 독자**: OMS/WMS/OpenMall/Rescue 등 Aimbase 를 사용하는 소비앱의 개발자·운영자.
> **이 문서 하나만 읽으면** 관리자 세팅 → BFF 구현 → 브라우저 삽입 → 트러블슈팅까지 순서대로 완결됩니다.

## 0. 전체 플로우 한눈에 보기

```
┌────────────────┐     ┌──────────────────┐     ┌───────────────────┐
│ 소비앱 브라우저   │     │  소비앱 BFF (BE)  │     │   Aimbase 서버     │
│ (oms.com 등)   │     │  API Key 보관 유일 │     │ (aimbase.com)     │
│                │     │                  │     │                   │
│ <aimbase-chat> │───▶│ /my-bff/aimbase- │───▶│ /api/v1/sessions/ │
│  authResolver()│     │   token          │     │  issue-widget-    │
│                │◀───│  (서버간 호출)     │◀───│   token (단기 JWT) │
│                │     └──────────────────┘     └───────────────────┘
│                │
│ 위젯 토큰으로 직결:                            ┌───────────────────┐
│ /chat/completions (SSE) ──────────────────▶│                   │
│ /workflows/runs/{id}/subscribe (SSE) ─────▶│    Aimbase API     │
│ /knowledge-sources/.../chunks/... ────────▶│                   │
└────────────────┘                          └───────────────────┘
```

**핵심 원칙**: 브라우저에 Aimbase API Key 를 절대 내려보내지 마세요. 소비앱 BFF 가 서버간 호출로 **30분 TTL 단기 JWT** 를 대리 발급받아 브라우저에 전달하는 3-Tier 구조가 유일한 안전 경로입니다.

---

## 1. 관리자 세팅 (Aimbase 운영자가 1회 수행)

소비앱을 새로 연결할 때마다 다음 2가지를 한다.

### 1-1. 소비앱 전용 API Key 발급

```bash
curl -X POST "$AIMBASE/api/v1/platform/api-keys" \
  -H "Authorization: Bearer $SUPER_ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
        "name":"OMS 위젯 BFF 키",
        "domainApp":"oms",
        "tenantId":"rescue_prod"
      }'
# 응답의 apiKey 값은 1회만 조회 가능. BFF 환경변수로 안전하게 전달.
```

### 1-2. 소비앱 Origin 을 화이트리스트에 추가

```sql
-- Aimbase 관리자 UI (Platform > Runtime Settings) 또는 psql 직접
UPDATE global_config
   SET config_value='https://oms.company.com,https://rescue.company.com',
       updated_by='ops', updated_at=NOW()
 WHERE config_key='widget.allowed-origins';
```

> CSV 로 여러 origin 지정 가능. 프로토콜 + 도메인 + 포트가 **정확히** 일치해야 허용. `*` 와일드카드 불가.

관련 설정 4종(상세 운영 절차는 `docs/guides/aimbase-ops-guide.md` § 2-5 참조):

| 키 | 기본 | 설명 |
|---|---|---|
| `widget.allowed-origins` | (빈 문자열) | 허용 Origin CSV |
| `widget.allowed-scopes` | `chat:stream,workflow:subscribe,rag:read` | 토큰 부여 가능 scope |
| `widget.token-ttl-seconds` | `1800` | 기본 TTL |
| `widget.token-max-ttl-seconds` | `3600` | 최대 TTL 하드캡 |

---

## 2. BFF 구현 (소비앱 백엔드 개발자)

### 2-1. 토큰 프록시 엔드포인트 하나만 만들면 됩니다

BFF 의 유일한 역할은 **API Key 를 env 에 보관**하고 **브라우저의 토큰 요청을 Aimbase 로 중계**하는 것. 필요 시 소비앱 자체 로그인 검증을 앞에 둔다.

#### Node.js (Express 없이 내장 http)

```js
const http = require("http");

async function issueWidgetToken() {
  const res = await fetch(`${process.env.AIMBASE_URL}/api/v1/sessions/issue-widget-token`, {
    method: "POST",
    headers: {
      "X-API-Key": process.env.AIMBASE_API_KEY,
      "X-Tenant-Id": process.env.AIMBASE_TENANT_ID,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      origin: process.env.ALLOWED_ORIGIN,  // 소비앱 브라우저 origin
      scopes: ["chat:stream", "workflow:subscribe", "rag:read"],
      ttl_seconds: 1800,
    }),
  });
  if (!res.ok) throw new Error(`Aimbase token ${res.status}: ${await res.text()}`);
  return (await res.json()).data; // { token, expires_at, refresh_after, scopes }
}

http.createServer(async (req, res) => {
  if (req.method === "POST" && req.url === "/my-bff/aimbase-token") {
    // TODO: 여기서 자체 사용자 세션 검증
    try {
      const token = await issueWidgetToken();
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(token));
    } catch (e) {
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: e.message }));
    }
  }
}).listen(3000);
```

> 완전한 샘플: Aimbase 공개 URL `GET /widget/v1/sample-bff/server.js` (파일 하나, 외부 의존 0).

#### Spring Boot (Java)

```java
@RestController
public class AimbaseTokenController {
    @Value("${aimbase.url}")        private String aimbaseUrl;
    @Value("${aimbase.api-key}")    private String apiKey;
    @Value("${aimbase.tenant-id}")  private String tenantId;
    @Value("${widget.allowed-origin}") private String origin;

    private final RestClient rest = RestClient.create();

    @PostMapping("/my-bff/aimbase-token")
    public Map<String, Object> issueToken() {
        // TODO: 여기서 SecurityContext 의 로그인 사용자 검증
        var body = Map.of("origin", origin,
                          "scopes", List.of("chat:stream", "workflow:subscribe", "rag:read"),
                          "ttl_seconds", 1800);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = rest.post()
            .uri(aimbaseUrl + "/api/v1/sessions/issue-widget-token")
            .header("X-API-Key", apiKey)
            .header("X-Tenant-Id", tenantId)
            .body(body)
            .retrieve().body(Map.class);
        return (Map<String, Object>) wrapper.get("data");
    }
}
```

#### FastAPI (Python)

```python
import os, httpx
from fastapi import APIRouter

router = APIRouter()

@router.post("/my-bff/aimbase-token")
async def issue_token():
    async with httpx.AsyncClient() as client:
        r = await client.post(
            f"{os.environ['AIMBASE_URL']}/api/v1/sessions/issue-widget-token",
            headers={
                "X-API-Key": os.environ["AIMBASE_API_KEY"],
                "X-Tenant-Id": os.environ["AIMBASE_TENANT_ID"],
            },
            json={
                "origin": os.environ["ALLOWED_ORIGIN"],
                "scopes": ["chat:stream", "workflow:subscribe", "rag:read"],
                "ttl_seconds": 1800,
            },
        )
    r.raise_for_status()
    return r.json()["data"]
```

### 2-2. 체크리스트

- [ ] API Key 는 **환경변수**에만 저장. 코드·로그·클라이언트 번들에 절대 넣지 않는다.
- [ ] BFF 엔드포인트 호출 전에 **자체 사용자 인증** 검증 (미인증 요청이 토큰을 받아가지 못하게).
- [ ] `origin` 파라미터는 BFF 가 **고정값**으로 주입하거나 검증 — 클라이언트가 마음대로 바꿀 수 없게.

---

## 3. 브라우저 삽입 (소비앱 프론트엔드 개발자)

소비앱은 위젯 번들을 **CDN 방식으로 쓰거나, 다운받아 자기 서버에 두거나** 자유롭게 선택.

### 3-1. CDN 방식 (Aimbase 가 정적 서빙)

```html
<script src="https://aimbase.company.com/widget/v1/aimbase-chat.umd.global.js"></script>
<aimbase-chat
  base-url="https://aimbase.company.com"
  token-endpoint="/my-bff/aimbase-token"
  display="bubble"
></aimbase-chat>
```

**장점**: Aimbase 가 위젯 업데이트하면 브라우저 다음 로드 시 자동 반영.
**단점**: Aimbase 서버/URL 에 의존. 오프라인·폐쇄망 불가.

### 3-2. 다운로드 방식 (소비앱 자체 호스팅)

```bash
# 소비앱 배포 스크립트에 한 번만 추가
curl -O https://aimbase.company.com/widget/v1/aimbase-chat.umd.global.js
# (소비앱 자체 정적 자산 경로로 이동)
mv aimbase-chat.umd.global.js public/assets/
```

```html
<script src="/assets/aimbase-chat.umd.global.js"></script>
<aimbase-chat base-url="https://aimbase.company.com"
              token-endpoint="/my-bff/aimbase-token"
              display="bubble"></aimbase-chat>
```

**장점**: Aimbase URL 변경·장애 영향 없음. 폐쇄망·규제 환경 가능. 버전 고정.
**단점**: Aimbase 가 버그 수정해도 수동으로 다시 받아 배포해야 반영.

### 3-3. npm 방식 (번들러 통합)

```bash
npm install @aimbase/chat-widget-embed
```

```ts
import { defineAimbaseChat, createWidget } from "@aimbase/chat-widget-embed";

// (A) Web Component 로 — HTML 에 <aimbase-chat> 사용
defineAimbaseChat();

// (B) JS API 로 직접 — React/Vue 컨테이너 내부
const handle = createWidget({
  baseUrl: "https://aimbase.company.com",
  authResolver: async () => {
    const res = await fetch("/my-bff/aimbase-token", { method: "POST" });
    return (await res.json()); // { token, expires_at, refresh_after }
  },
  display: "inline",
  target: document.getElementById("chat-container"),
  ragSourceId: "kb_rescue",
  contextProvider: () => ({ orderId: currentOrderId, page: "order-detail" }),
  on: {
    onWorkflowStep: (ev) => console.log("workflow", ev),
    onApprovalRequired: (ev) => openMyOwnApprovalFlow(ev),
  },
});
```

### 3-4. 주요 옵션

| 속성 | 값 | 기본 | 설명 |
|------|-----|------|------|
| `base-url` | URL | 필수 | Aimbase 서버 URL |
| `token-endpoint` | URL | — | BFF 프록시 경로 (authResolver 를 JS 로 주입 시 생략 가능) |
| `display` | `bubble` \| `inline` \| `panel` | `inline`(CE) | 렌더 모드 |
| `session-id` | string | auto UUID | 대화 세션 |
| `rag-source-id` | string | — | RAG 소스 지정 시 citations 자동 표시 |
| `theme-mode` | `light` \| `dark` \| `auto` | `auto` | 테마 |

JS API (`createWidget` / `<aimbase-chat>.property`) 에서는 추가로:
- `contextProvider`: 현재 화면 컨텍스트(예: `{orderId}`)를 매 대화 자동 주입
- `on.onMessage` / `on.onWorkflowStep` / `on.onApprovalRequired` / `on.onError` / `on.onTokenExpiring`

---

## 4. UI 모드 선택 가이드

| 모드 | 용도 예시 |
|------|---------|
| `bubble` | 전역 도우미 — 모든 페이지 우하단. 사용자가 필요할 때 연다 |
| `inline` | 특정 기능 내장 — 주문 상세 페이지에서 "이 주문 환불 처리해줘" 전용 채팅 영역 |
| `panel` | 관리자 도구 — 항상 열린 측면 패널. 워크플로우 스튜디오 병행 작업 |

---

## 5. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 위젯 마운트 시 CORS 에러 | `widget.allowed-origins` 에 소비앱 origin 미등록 | 관리자가 CSV 추가 (§ 1-2). 프로토콜·포트 정확히 일치해야 함 |
| `401 API Key is required` | BFF 가 JWT 로 Aimbase 호출 | 반드시 `X-API-Key` 헤더로 호출 |
| `400 origin is not allowed` | 토큰 발급 body 의 `origin` 과 화이트리스트 불일치 | 둘을 맞춘다. 프로토콜·포트 포함 |
| `403` 가 간헐 발생 | 위젯 토큰 만료 | 위젯이 자동 갱신하지만 네트워크 오류 시 실패 가능. `on.onTokenExpiring` 콜백에서 재시도 |
| `done` 이벤트에 citations 없음 | `rag-source-id` 미지정 또는 검색 결과 0건 | 속성/옵션에 `ragSourceId` 지정 + 해당 소스에 인제스트된 문서 확인 |
| 워크플로우 subscribe 연결은 되는데 이벤트 없음 | 이미 종료된 run | `workflow.snapshot` + `workflow.done` 1회씩만 오고 close 되는 정상 동작 |

---

## 6. 서버 API 상세 (참고)

상세한 엔드포인트 스펙·요청/응답 포맷은 별도 문서:
- **API 레퍼런스**: `docs/guides/aimbase-api-guide.md` § 17
- **운영 가이드**: `docs/guides/aimbase-ops-guide.md` § 2-5, 시나리오 N
- **위젯 패키지 README**: `packages/chat-widget-embed/README.md`

## 7. 빌드 산출물 (공개 URL)

| URL | 용도 |
|-----|------|
| `/widget/v1/aimbase-chat.umd.global.js` | `<script>` 로드 (약 17 KB) |
| `/widget/v1/aimbase-chat.esm.js` | 번들러 import (약 25 KB) |
| `/widget/v1/aimbase-chat.d.ts` | TypeScript 타입 |
| `/widget/v1/guide.html` | 이 가이드의 HTML 버전 (공개 서빙) |

위 모두 **인증 없이** 접근 가능. 소비앱은 이 URL 을 `<script src>` 로 직접 쓰거나 curl 로 받아 자기 서버에 배치할 수 있다.

---

## 8. 후속 기능 (현재 범위 외)

- CR-059: Vue/Svelte 전용 래퍼
- CR-060: 음성 입력 (STT) UI
- CR-061: 파일 업로드
- CR-062: CDN 배포 인프라 (필요 시 외부 CDN 도입)
- 위젯 내 승인 UI (`allowApproval: true`) — 현재는 이벤트만 발행, 소비앱 자체 결재 플로우 재사용

**문의**: CR-058 관련 개선 요청은 Aimbase 프로젝트 저장소 이슈로.
