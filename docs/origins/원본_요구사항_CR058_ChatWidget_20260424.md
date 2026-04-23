# 원본 요구사항 — CR-058: Aimbase Chat Widget SDK (임베드형)

**대화 일자**: 2026-04-24
**관련 CR**: CR-058 (신규 발번)
**선행/연관 CR**:
- CR-040 (런타임 설정 관리) — `platform_settings`에 `widget.allowed_origins` 저장
- CR-045 (대화형 채팅 UI + SSE 이벤트 5종) — 위젯이 소비하는 Chat SSE 구현체
- CR-046 (Chat abort) — `/api/v1/chat/{sessionId}/abort` 재사용
- CR-011 (Citation 인프라) — `RAGService.buildContextWithCitations()` 메서드는 구현되어 있으나 호출 미활성

---

## 발단 — 소비앱 증가에 따른 채팅 UI 중복 비용

기획자의 질문:
> "Aimbase를 활용하는 모든 소비앱의 UI에 채팅창을 (sse?) 쉽게 붙일 수 있는 방법이 있을까요?"

현재 Aimbase는 Chat SSE API(`/api/v1/chat/completions`)와 5개 이벤트(delta/thinking/tool_use_start/tool_result/done)가 완성되어 있어 소비앱이 직접 호출하는 것은 가능하나, **소비앱 5개 이상(OMS/WMS/OpenMall/Rescue/Notification 등)** 이 각자 채팅 UI를 구현하면 중복 비용이 커진다.

---

## 범위 확정 — 채팅 + 워크플로우 + RAG

기획자 지시:
> "채팅 + 워크플로우 실행 + RAG 소스 표시 → 이것까지죠"

위젯이 제공해야 하는 3가지:
1. **채팅 스트리밍** — SSE 토큰 렌더, 중단, 세션 복원
2. **워크플로우 실행 가시화** — 스텝 PENDING→RUNNING→DONE/FAIL, 서브워크플로우, REQUIRE_APPROVAL 처리
3. **RAG 출처 카드** — citation 리스트 + 원문 미리보기

---

## Phase 1 — 현황 조사 결과 (3개 Explore 에이전트 병렬)

### 이미 있는 것 (활용)
- `POST /api/v1/chat/completions` (stream=true) — SSE 이벤트 5종 완성
- `POST /api/v1/chat/{sessionId}/abort` (CR-046)
- 세션 관리 — POST `/api/v1/conversations`, PUT `/meta`, 24h Redis TTL, JSONB meta 커스텀 필드 가능
- 인증 — JWT Bearer + API Key 2종
- 테넌트 바인딩 — `TenantResolver` @Order(-200), `X-Tenant-Id` 또는 `tenant_id` 쿼리
- RAG 검색 결과 모델 — `RetrievedChunk(content, score, metadata, sourceId)`, `KnowledgeSource(name)`
- **`RAGService.buildContextWithCitations():219`** — 이미 구현, 호출만 활성화하면 동작

### 없어서 신규 구현 필요 (서버 선행)
1. **CORS 설정 부재** — Spring Boot 기본값(모든 오리진 거부) 상태. 타 도메인 위젯 프리플라이트 실패
2. **단기 위젯 토큰 API 없음** — 브라우저에 API Key 노출 금지 원칙상 필수
3. **워크플로우 진행 이벤트 0% 구현** — REST 폴링만 가능, SSE 스트리밍 엔드포인트 없음
4. **REQUIRE_APPROVAL 실시간 통지 없음** — DB 폴링만 가능
5. **`WorkflowRunEntity.parent_run_id` 필드 부재** — 서브워크플로우 펼침 UI 불가
6. **`ChatResponse.citations` 필드 부재** — `buildContextWithCitations()` 호출 안 됨
7. **청크 원문 조회 API 없음** — 원문 미리보기 패널 구현 불가
8. **`rag_used` 플래그 없음** — citation 표시 여부 판단 불가

---

## 설계 결정 — 사용자 확인

### D1. 소비앱 지원 범위
- **결정**: React npm + Web Component 둘 다
- **이유**: Vue/레거시 HTML 페이지까지 커버. iframe은 공수는 작지만 소비앱 5개+ 시점부터 통합도 저하로 결국 SDK 전환 필요.

### D2. 위젯 내 승인 UI 포함 여부
- **결정**: 이벤트만 발행 (`allowApproval: false` 기본)
- **이유**: 소비앱별 결재 체계(OMS 주문 승인, Rescue 반품 결재 등) 재사용. 위젯은 `workflow.approval` 이벤트만 발행.

### D3. 스프린트 분할
- **결정**: Sprint 52(서버 3 Phase, 10MD) + Sprint 53(프론트+E2E, 10MD)
- **이유**: 한 스프린트 압축 시 capacity 초과, 완결성 타협. 서버 먼저 끝내면 curl로 검증 가능.

### 보조 결정 (구현 중 자체 확정)
- **D4. `allowed_origins` 저장소**: `platform_settings` JSONB (CR-040 재사용, 관리자 UI 공수 0)
- **D5. 청크 원문 조회 경로**: 위젯 → Aimbase 직결 (`rag:read` scope로 제한)
- **D6. 모노레포 구조**: pnpm workspace — `apps/console/` + `packages/chat-widget/` + `packages/chat-widget-embed/`
- **D7. 워크플로우 SSE 스트림**: chat SSE와 분리 (`/workflows/runs/{id}/subscribe`)
- **D8. UI 모드**: `display: 'bubble' | 'inline' | 'panel'` 3종 (Intercom 스타일), 기본 `bubble`

---

## 아키텍처 — 3-Tier (브라우저 ↔ 소비앱 BFF ↔ Aimbase)

```
┌──────────────────┐     ┌──────────────────────┐     ┌────────────────────┐
│ 소비앱 브라우저     │     │  소비앱 BFF (BE)      │     │   Aimbase BE        │
│ (oms.com 등)     │     │  API Key 보관 유일     │     │ (aimbase.com)       │
│                  │     │                      │     │                     │
│ <aimbase-chat>   │───▶│ POST /my-bff/aimbase- │───▶│ POST /api/v1/       │
│   authResolver() │     │   token              │     │   sessions/issue-   │
│                  │◀───│  (장기 API Key 보유)    │◀───│   widget-token      │
│                  │     └──────────────────────┘     │  (30min JWT 발급)    │
│                  │                                  │                     │
│ 위젯 토큰으로 직결:                                     │                     │
│  /chat/completions (SSE) ────────────────────────▶│                     │
│  /workflows/runs/{id}/subscribe (SSE) ───────────▶│                     │
│  /knowledge-sources/{sid}/chunks/{cid} ──────────▶│                     │
└──────────────────┘                                └────────────────────┘
```

**BFF 프록시 필수 이유**: API Key는 테넌트 전체 권한. 브라우저 번들에 노출되면 DevTools로 탈취 → 타 테넌트 도용 즉시 가능. BFF가 서버간 호출로 단기 토큰(scope 축소 + TTL 30분)을 대리 발급 → 브라우저엔 위젯 토큰만.

---

## 위젯 공개 API (확정)

```ts
initAimbaseChat({
  baseUrl: string,
  authResolver: () => Promise<{token: string; expiresAt: number}>,
  contextProvider?: () => Record<string, unknown>,      // 현재 화면 컨텍스트 자동 주입
  display?: 'bubble' | 'inline' | 'panel',               // 기본 'bubble'
  workflow?: {
    enabled?: boolean,                                   // 기본 true
    allowApproval?: boolean,                             // 기본 false (이벤트만 발행)
    visualizationMode?: 'inline' | 'drawer',
  },
  rag?: {
    previewMode?: 'side-panel' | 'modal' | 'hidden',
    onCitationClick?: (c: Citation) => void,
  },
  theme?: {
    mode?: 'light' | 'dark' | 'auto',
    cssVars?: Record<string, string>,
  },
  on?: {
    onMessage?, onWorkflowStep?, onApprovalRequired?,
    onError?, onTokenExpiring?,
  }
})
```

---

## 산출물 요약

### Sprint 52 — 서버 (10 MD)
- Phase 1 (3MD): CORS + 단기 위젯 토큰 + Scope 게이트
- Phase 2 (2MD): RAG Citations 활성화 + 청크 원문 API
- Phase 3 (5MD): 워크플로우 SSE + parent_run_id + Event Publisher

### Sprint 53 — 프론트 + E2E (10 MD)
- Phase 4 (5MD): React `@aimbase/chat-widget` 패키지
- Phase 5 (3MD): UMD + Web Component `<aimbase-chat>`
- Phase 6 (2MD): 소비앱 통합 가이드 + 샘플 앱 + E2E 9 시나리오

---

## 참고 — 업계 관행 검토

위젯 SDK 방식은 B2B/B2C SaaS 챗봇 시장 표준 패턴:
- **SDK + Web Component (~60%)**: Intercom, Drift, Crisp, Chatbase
- **iframe (~30%)**: MS Copilot Studio, Zendesk Web Widget 일부
- **헤드리스 라이브러리 (~10%)**: Vercel AI SDK `useChat`, LangChain.js

Aimbase는 소비앱 5개+, 테넌트별 테마, 화면 컨텍스트 주입이 필요해 **SDK + Web Component 병행**이 최적.

---

## 결정되지 않은 것 (구현 중 확정 또는 차후 CR)

- 위젯 CDN 배포 인프라(사내 S3 vs 공개 npm+CDN) — CR-058 말미 또는 별도 운영 논의
- Vue/Svelte 프레임워크 전용 래퍼 — 필요 시 CR-059로 분리
- 음성 입력(STT) — 현재 CR-011 인프라 있으나 위젯 UI는 이번 범위 제외
- 파일 업로드 위젯 지원 — 범위 제외 (텍스트 + 이미지 URL만)

---

**작성**: 2026-04-24 대화 세션
**파일 경로**: `docs/origins/원본_요구사항_CR058_ChatWidget_20260424.md`
