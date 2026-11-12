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
| `connection-id` | string | 테넌트 기본 | 쓸 연결. 모델 목록과 깊게 생각하기 단추가 이 연결을 따른다 |
| `model` | string | 연결 기본 모델 | 처음 고른 모델. 연결의 허용 모델에 없으면 연결 기본 모델로 시작한다 |
| `suggestions` | JSON 배열 또는 언어별 JSON | 없음 | 첫 화면 추천 질문, 최대 4개. 예 `'["고객이 원하는 것은?","답변 초안"]'`. 화면 언어마다 다르게 주려면 `'{"ko":["주문 현황"],"en":["Order status"]}'` (CR-175, CR-177) |
| `empty-title` | string 또는 언어별 JSON | 위젯 기본 문구 | 첫 화면 안내 제목. `{` 로 시작하면 `'{"ko":"무엇을 도와드릴까요?","en":"How can I help?"}'` 처럼 언어별 글로 읽는다 (CR-175, CR-177) |
| `empty-subtitle` | string 또는 언어별 JSON | 없음 | 첫 화면 안내 한 줄. 언어별 글은 `empty-title` 과 같은 모양 (CR-175, CR-177) |
| `locale` | `ko` \| `en` | `ko` | 화면 언어 기본값. `en-US` 처럼 지역이 붙어도 된다. 사용자가 더 보기에서 바꾸면 그 브라우저에서는 그 값을 따른다 (CR-177) |
| `font-size` | `small` \| `medium` \| `large` | `medium` | 글자 크기 기본값. 사용자가 더보기에서 바꾸면 그 브라우저에서는 그 값을 따른다 (CR-175) |

JS API (`createWidget` / `<aimbase-chat>.property`) 에서는 추가로:
- `contextProvider`: 현재 화면 컨텍스트(예: `{orderId}`)를 매 대화 자동 주입
- `on.onMessage` / `on.onWorkflowStep` / `on.onApprovalRequired` / `on.onError` / `on.onTokenExpiring`
- `suggestions`, `emptyTitle`, `emptySubtitle`, `fontSize`, `locale`: 위 속성과 같다. 언어별 값은 `{ ko: ..., en: ... }` 객체로 준다 (CR-175, CR-177)
- `on.onNewConversation({previousSessionId, sessionId})`: 사용자가 새 대화를 시작했을 때. 엘리먼트로 붙였으면 `new-conversation` 이벤트(`detail` 에 같은 값). 앞 대화는 서버에 남는다 (CR-175)

### 3-5. 위젯이 스스로 하는 것 (CR-175, CR-179, CR-180, CR-184, CR-182, CR-181)

붙이는 앱이 따로 만들 것 없이 위젯 안에 들어 있다.

| 순번 | 기능 | 붙이는 앱이 할 일 |
|---|---|---|
| 1 | 응답 중지(Esc), 다시 생성, 질문 수정, 복사, 새 대화, 크게 보기 | 없음 |
| 2 | 좋아요, 아쉬워요(이유와 한 줄 의견) | 없음. 운영자가 Aimbase 관리 화면 「응답 평가」에서 본다 |
| 3 | 모델 선택 | 없음. 운영자가 연결의 「허용 모델」에 둘 이상 넣어야 보인다 |
| 4 | 깊게 생각하기 | 없음. 연결이 지원할 때만 보인다 |
| 5 | 후속 질문 칩과 대화 제목 | 없음. 운영자가 런타임 설정에서 끌 수 있다 |
| 6 | 첫 화면 추천 질문 | `suggestions` 로 질문을 준다 |
| 7 | 글자 크기 | 기본값만 `font-size` 로 줄 수 있다 |
| 8 | AI 가 만든 파일을 옆 창에서 미리보기(pptx, docx, pdf, xlsx, csv, HTML, SVG, 그림, 마크다운, 글). 슬라이드는 「발표」로 화면 가득 넘긴다 | 없음. 파일 칩을 누르면 열리고, 받기는 칩 끝과 옆 창의 「다운로드」 단추로 한다 |
| 9 | 응답 안 HTML 코드 블록의 「실행」, SVG 코드 블록의 그림 보기와 「옆 창에서 보기」 | 없음. HTML 은 격리된 틀에서 돌고 바깥 주소로 데이터를 보내지 못한다 |
| 10 | + 메뉴 「그림 만들기」 와 그림 아래 크게 보기, 다운로드, 「그림 고치기」 (CR-184) | 없음. 운영자가 그 대화 연결에 「그림 연결」 을 선택해야 메뉴가 보인다(운용 가이드 § 2-16) |
| 11 | 화면 언어(한국어, English) | 기본값만 `locale` 로 줄 수 있다. 붙이는 앱이 주는 글은 언어별로 줄 수 있다 (CR-177) |
| 12 | AI 가 만든 워드, PDF, 엑셀, PPT, CSV 파일을 파일 칩으로 받기 (CR-182) | 없음. 운영자가 런타임 설정 `chat.file-create.enabled` 로 끌 수 있다 |
| 13 | 응답 아래 「저장」(PDF, 워드), 더 보기 「대화 내보내기」(PDF, 워드, 마크다운) (CR-182) | 없음. PDF 는 Aimbase 서버에 한글 글꼴이 있을 때만 보인다 |
| 14 | 더 보기 「공유 링크 만들기」, 파일 칩 끝 「공유」. 그 순간의 사본을 링크를 가진 사람이 기한 안에 로그인 없이 연다 (CR-182) | 없음. 운영자가 공개 주소 `chat.share.public-base-url` 을 넣어야 보인다(운용 가이드 § 2-17) |
| 15 | 더 보기 「기억과 선호」: 내 선호 글, 자동 기억 목록과 삭제, 켜고 끄기. 마지막에 선택한 모델과 깊게 생각하기를 새 대화에 다시 적용 (CR-179) | 토큰 발급 때 `user_ref` 를 사람마다 다르게 준다. 없으면 메뉴가 보이지 않는다. 새 대화의 처음 모델은 마지막에 선택한 모델, `model` 옵션, 연결 기본 모델 순이다 |
| 16 | 옆 창 아래 입력칸으로 문서를 대화로 고치기, 선택한 부분만 고치기, 버전 「이전」, 「다음」과 「이 버전으로 되돌리기」 | 없음. 원본 없는 파일(사람이 올린 pptx 등)은 실행기 연결에서만 입력칸이 보인다. 파일 만들기(`chat.file-create.enabled`)를 끄면 AI 가 만든 파일에도 입력칸이 없다 |

위젯 토큰에 `chat:stream` 이 있어야 한다. 마이크 단추는 토큰 scopes 에 `chat:stt` 가 있을 때만 보이므로, BFF 는 Aimbase 가 준 scopes 를 위젯에 그대로 넘긴다. 파일 미리보기는 받기와 같은 권한이라 토큰 scopes 에 `chat:upload` 가 있어야 한다. 서버 API 는 API 가이드 § 28, § 29, § 32, 운영 설정은 운용 가이드 § 2-14, § 2-18.

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
| 모델 선택 칸이 없다 | 연결의 허용 모델이 비었거나 하나 | 운영자가 연결 「허용 모델」에 둘 이상 넣는다 (운용 가이드 § 2-14) |
| 깊게 생각하기 단추가 없다 | 그 연결이 요청마다 생각을 바꾸지 못함 | Anthropic API 나 Claude CLI 연결을 쓴다 |
| 다시 생성, 평가 단추가 없다 | Aimbase 서버가 CR-175 전 판이라 `done` 에 `turn_seq` 가 없음 | 서버를 올린다 |
| 마이크 단추가 없다 | 위젯에 넘긴 토큰 scopes 에 `chat:stt` 가 없음 | BFF 가 받은 scopes 를 그대로 넘긴다 |
| 파일 칩을 눌러도 받기만 된다 | Aimbase 서버가 CR-180 전 판이라 파일 목록에 미리보기 종류가 없음 | 서버를 올린다 |
| HTML 「실행」 화면에서 스크립트나 바깥 그림이 돌지 않는다 | 붙이는 앱 화면의 보안 정책(CSP)이 실행 틀에도 걸림 | 앱의 CSP 가 그 스크립트와 주소를 허용해야 한다 |
| 「공유 링크 만들기」, 「공유」 가 없다 | Aimbase 런타임 설정에 공개 주소가 없음 | 운영자에게 `chat.share.public-base-url` 을 요청한다 |
| 「저장」, 「대화 내보내기」 가 없다 | Aimbase 서버가 CR-182 전 판이라 위젯 설정에 형식 목록이 없음 | 서버를 올린다 |

---

## 6. 서버 API 상세 (참고)

상세한 엔드포인트 스펙·요청/응답 포맷은 별도 문서:
- **API 레퍼런스**: `docs/guides/aimbase-api-guide.md` § 17, § 28
- **운영 가이드**: `docs/guides/aimbase-ops-guide.md` § 2-5, § 2-14, 시나리오 N
- **위젯 패키지 README**: `packages/chat-widget-embed/README.md`

## 7. 빌드 산출물 (공개 URL)

| URL | 용도 |
|-----|------|
| `/widget/v1/aimbase-chat.umd.global.js` | `<script>` 로드 (약 17 KB) |
| `/widget/v1/aimbase-chat.esm.js` | 번들러 import (약 25 KB) |
| `/widget/v1/aimbase-chat.d.ts` | TypeScript 타입 |
| `/widget/v1/guide.html` | 이 가이드의 HTML 버전 (공개 서빙) |

위 모두 **인증 없이** 접근 가능. 소비앱은 이 URL 을 `<script src>` 로 직접 쓰거나 curl 로 받아 자기 서버에 배치할 수 있다.

**브라우저 캐시 (2026-11-12, CR-175)**: 위 파일들은 이름이 판마다 같으므로 브라우저가 열 때마다 바뀌었는지 묻게 둔다(`Cache-Control: no-cache`, 바뀌지 않았으면 304). 이름 끝에 내용 해시가 붙은 나머지 조각(`chunk-XXXXXXXX.js` 등)만 1년 캐시(`immutable`)다. 그래서 Aimbase 를 배포하면 소비앱이 할 일 없이 다음 열 때 새 위젯이 뜬다.

그 전에는 모든 파일이 30일 캐시였다. 그때 받아 둔 사본은 기한까지 브라우저가 다시 묻지 않으므로, 소비앱은 **한 번만** 로더 주소 끝에 판 표시를 붙인다(예 `aimbase-chat.umd.global.js?v=2`). 로더는 자기 주소의 쿼리를 그대로 esm 주소에 붙여 부르므로 두 파일 모두 옛 사본을 건너뛴다. 표시는 Aimbase 가 이 변경을 반영한 뒤에 붙인다. 먼저 붙이면 새 주소에 옛 30일 설정이 다시 걸린다.

---

## 8. 후속 기능 (현재 범위 외)

- CR-059: Vue/Svelte 전용 래퍼
- CR-060: 음성 입력 (STT) UI
- CR-061: 파일 업로드
- CR-062: CDN 배포 인프라 (필요 시 외부 CDN 도입)
- 위젯 내 승인 UI (`allowApproval: true`) — 현재는 이벤트만 발행, 소비앱 자체 결재 플로우 재사용

**문의**: CR-058 관련 개선 요청은 Aimbase 프로젝트 저장소 이슈로.
