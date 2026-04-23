# T3-9 | CR-058 Aimbase Chat Widget SDK 상세 설계서

**문서 번호**: T3-9
**관련 CR**: CR-058
**작성일**: 2026-04-24
**상태**: ✅ Sprint 52 + Sprint 53 완료 (2026-04-24). 서버 3 Phase + 프론트 축소 MVP(Web Component + UMD 번들 17KB) + 샘플 BFF + 샘플 consumer HTML. 후속 CR-059~062 로 분리.
**원본 요구사항**: [docs/origins/원본_요구사항_CR058_ChatWidget_20260424.md](origins/원본_요구사항_CR058_ChatWidget_20260424.md)

---

## 1. 목적과 범위

### 1.1 목적
Aimbase를 사용하는 소비앱(OMS/WMS/OpenMall/Rescue/Notification 등, 모두 타 도메인)이 **한 줄 삽입으로 채팅 + 워크플로우 실행 가시화 + RAG 출처 카드**를 자기 UI에 얹을 수 있는 임베드형 SDK를 구축한다. 채팅 UI 중복 구현 비용을 제거하고, 소비앱이 비즈니스 로직에 집중하도록 한다.

### 1.2 범위 — 포함

**서버 (Sprint 52, 10MD)**:
- Phase 1 — CORS 설정 + 단기 위젯 토큰 발급 API + Scope 기반 엔드포인트 게이트 (3MD)
- Phase 2 — RAG Citations 활성화 + 청크 원문 조회 API (2MD)
- Phase 3 — 워크플로우 SSE 구독 엔드포인트 + `parent_run_id` 컬럼 + WorkflowEventPublisher (5MD)

**프론트 (Sprint 53, 10MD)**:
- Phase 4 — React 패키지 `@aimbase/chat-widget` (5MD)
- Phase 5 — UMD 번들 + Web Component `<aimbase-chat>` (3MD)
- Phase 6 — 소비앱 통합 가이드 + 샘플 BFF/데모 앱 + E2E 9 시나리오 (2MD)

### 1.3 범위 — 제외
- Vue/Svelte 전용 래퍼 — 필요 시 CR-059 분리
- 음성 입력(STT) UI — CR-011 인프라는 있으나 위젯 UI 제외
- 파일 업로드 — 텍스트 + 이미지 URL만
- CDN 배포 인프라 결정 — 운영 논의 별도
- `allowApproval: true` 모드의 위젯 내 승인 UI — 기본값 false만 구현, true 옵션은 API 자리만 확보

### 1.4 선행 의존성
- **CR-040** `PlatformSettingsService` — `widget.allowed_origins` 저장 + 관리자 UI 재사용
- **CR-045** Chat SSE 5 이벤트 — 위젯이 소비
- **CR-046** `/chat/{sessionId}/abort` — 위젯 중단 버튼
- **CR-011** `RAGService.buildContextWithCitations()` — 호출만 활성화
- **CR-051** SSE 가상스레드 SecurityContext 전파 — 워크플로우 SSE에 동일 패턴 적용

---

## 2. 아키텍처 — 3-Tier

```
┌──────────────────┐     ┌──────────────────────┐     ┌────────────────────┐
│ 소비앱 브라우저     │     │  소비앱 BFF (BE)      │     │   Aimbase BE        │
│ oms.company.com  │     │  API Key 보관 유일     │     │ aimbase.company.com│
│                  │     │                      │     │                     │
│ <aimbase-chat>   │───▶│ POST /my-bff/        │───▶│ POST /api/v1/       │
│   authResolver() │     │   aimbase-token      │     │  sessions/issue-    │
│                  │◀───│  (서버간 호출)         │◀───│  widget-token       │
│                  │     └──────────────────────┘     │                     │
│                  │                                  │                     │
│ 위젯 토큰으로 직결:                                     │                     │
│  /chat/completions (SSE)  ───────────────────────▶│                     │
│  /workflows/runs/{id}/subscribe (SSE) ───────────▶│                     │
│  /knowledge-sources/{sid}/chunks/{cid} ──────────▶│                     │
└──────────────────┘                                └────────────────────┘
```

### 2.1 왜 BFF 프록시가 필수인가
테넌트 API Key(`users.api_key_hash` 또는 `api_keys` 테이블)는 장기·전체 권한 크리덴셜. 브라우저 번들에 노출되면:
1. DevTools `window.__AIMBASE__` 등에서 즉시 추출 가능
2. 탈취 후 `/api/v1/platform/**` 관리 API 호출 시도
3. 다른 테넌트의 세션 도용 가능

BFF가 서버간 호출로 **단기 JWT(30분 TTL + scope 화이트리스트)** 를 대리 발급 → 브라우저엔 좁은 권한만.

### 2.2 토큰 생애주기

```
[발급]      소비앱 BFF → Aimbase → JWT(exp=30min, scopes=[chat:stream, workflow:subscribe, rag:read])
[사용]      위젯 → Aimbase (Authorization: Bearer <token> 또는 ?access_token=<token>)
[갱신]      위젯이 exp - 5min 시점에 onTokenExpiring 발행 → authResolver() 재호출 → 신규 토큰
[만료]      만료 후 요청 시 401 → 위젯이 authResolver() 즉시 호출 후 재시도 1회
```

---

## 3. 서버 설계 — Sprint 52

### 3.1 Phase 1 — CORS + 단기 위젯 토큰

#### 3.1.1 `CorsConfig.java` 신규
[backend/platform-core/src/main/java/com/platform/config/CorsConfig.java](../backend/platform-core/src/main/java/com/platform/config/CorsConfig.java)

```java
@Configuration
public class CorsConfig {
    @Bean
    CorsConfigurationSource corsConfigurationSource(PlatformSettingsService settings) {
        return request -> {
            String origin = request.getHeader("Origin");
            if (origin == null) return null;

            // 전역 + 테넌트별 화이트리스트 (PlatformSettings 캐시 활용)
            List<String> globalAllowed = settings.getStringList("widget.allowed_origins", List.of());
            List<String> tenantAllowed = resolveTenantAllowedOrigins(request);
            Set<String> allowed = union(globalAllowed, tenantAllowed);

            if (!matchesAny(origin, allowed)) return null;  // CORS 거부

            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(List.of(origin));
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("Authorization", "X-Api-Key", "X-Tenant-Id",
                                          "Content-Type", "Accept", "Last-Event-ID"));
            cfg.setExposedHeaders(List.of("Cache-Control", "Connection"));
            cfg.setAllowCredentials(false);  // 토큰 방식만 사용
            cfg.setMaxAge(Duration.ofMinutes(10));
            return cfg;
        };
    }
}
```

**적용 범위**: `/api/v1/chat/**`, `/api/v1/workflows/**`, `/api/v1/conversations/**`, `/api/v1/knowledge-sources/**`, `/api/v1/sessions/issue-widget-token`

#### 3.1.2 `SecurityConfig.java` 변경
[backend/platform-core/src/main/java/com/platform/config/SecurityConfig.java:52-82](../backend/platform-core/src/main/java/com/platform/config/SecurityConfig.java#L52-L82)

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .cors(Customizer.withDefaults())  // ← CR-058 신규
        .csrf(CsrfConfigurer::disable)
        // ... 기존 체인
        .build();
}
```

- `@EnableMethodSecurity(prePostEnabled = true)` 추가 (`SecurityConfig` 클래스 레벨)

#### 3.1.3 `platform_settings` seed
[backend/platform-core/src/main/resources/db/migration/master/V{next}__seed_widget_settings.sql](../backend/platform-core/src/main/resources/db/migration/master/)

```sql
INSERT INTO platform_settings (setting_key, setting_value, setting_type, description)
VALUES
  ('widget.allowed_origins', '[]', 'JSON_ARRAY',
   '위젯 임베드를 허용할 전역 Origin 화이트리스트 (테넌트별 allowed_origins와 합집합)'),
  ('widget.token_default_ttl_seconds', '1800', 'INTEGER',
   '위젯 토큰 기본 TTL (초). 최대 3600 하드캡.'),
  ('widget.token_max_ttl_seconds', '3600', 'INTEGER',
   '위젯 토큰 최대 TTL 하드캡 (초).'),
  ('widget.allowed_scopes', '["chat:stream","workflow:subscribe","rag:read"]', 'JSON_ARRAY',
   '위젯 토큰에 허용되는 scope 화이트리스트 (요청과 교집합 부여)')
ON CONFLICT (setting_key) DO NOTHING;
```

#### 3.1.4 `POST /api/v1/sessions/issue-widget-token` 신규
[backend/platform-core/src/main/java/com/platform/api/WidgetTokenController.java](../backend/platform-core/src/main/java/com/platform/api/WidgetTokenController.java)

**요청 (인증: API Key만 허용, JWT 불가)**:
```json
{
  "project_id": "rescue",
  "user_ref": "user_123",
  "session_hint": "order_detail_page",
  "ttl_seconds": 1800,
  "origin": "https://oms.company.com",
  "scopes": ["chat:stream", "workflow:subscribe", "rag:read"]
}
```

**응답**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_at": "2026-04-24T11:30:00Z",
  "refresh_after": 1500
}
```

**검증 로직**:
1. `ttl_seconds` ≤ `widget.token_max_ttl_seconds` (초과 시 cap, 경고 로그)
2. `scopes` ∩ `widget.allowed_scopes` 만 부여 (교집합)
3. `origin` ∈ `widget.allowed_origins` ∪ 테넌트별 화이트리스트 (불일치 시 400)
4. `refresh_after` = `ttl_seconds - 300` (5분 여유)

#### 3.1.5 `JwtProvider.generateWidgetToken`
[backend/platform-core/src/main/java/com/platform/auth/JwtProvider.java:37-51](../backend/platform-core/src/main/java/com/platform/auth/JwtProvider.java#L37-L51)

```java
public String generateWidgetToken(String tenantId, String projectId, String userRef,
                                  List<String> scopes, String origin, long ttlSec) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userRef)
        .claim("tenant_id", tenantId)
        .claim("project_id", projectId)
        .claim("user_ref", userRef)
        .claim("scopes", scopes)
        .claim("origin", origin)
        .claim("type", "widget")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSec)))
        .signWith(key)
        .compact();
}
```

기존 `generateAccessToken()`과 **동일 시크릿** 재사용 — 검증 분기는 `type` claim으로.

#### 3.1.6 `JwtAuthenticationFilter` 분기
[backend/platform-core/src/main/java/com/platform/auth/JwtAuthenticationFilter.java:26-108](../backend/platform-core/src/main/java/com/platform/auth/JwtAuthenticationFilter.java#L26-L108)

```java
if ("widget".equals(claims.get("type"))) {
    // 1. Origin 검증
    String originClaim = claims.get("origin", String.class);
    String reqOrigin = request.getHeader("Origin");
    if (reqOrigin != null && !reqOrigin.equals(originClaim)) {
        response.setStatus(403);
        return;
    }

    // 2. Scope → GrantedAuthority
    List<String> scopes = claims.get("scopes", List.class);
    List<GrantedAuthority> authorities = scopes.stream()
        .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
        .toList();

    // 3. TenantContext + SecurityContext
    TenantContext.setCurrentTenantId(claims.get("tenant_id", String.class));
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(
            claims.get("user_ref", String.class), null, authorities));
} else {
    // 기존 access 토큰 로직
}
```

#### 3.1.7 엔드포인트 Scope 게이트

| 엔드포인트 | Scope |
|----------|-------|
| `POST /api/v1/chat/completions` | `chat:stream` |
| `POST /api/v1/chat/{sessionId}/abort` | `chat:stream` |
| `GET /api/v1/conversations/{sessionId}` | `chat:stream` |
| `GET /api/v1/workflows/runs/{runId}/subscribe` | `workflow:subscribe` |
| `GET /api/v1/workflows/{id}/runs/{runId}` | `workflow:subscribe` |
| `GET /api/v1/knowledge-sources/{sid}/chunks/{cid}` | `rag:read` |

```java
@PreAuthorize("hasAuthority('SCOPE_chat:stream') or authenticated")
// 또는 위젯 전용 강제:
@PreAuthorize("hasAuthority('SCOPE_chat:stream')")
```

**중요**: 관리용 엔드포인트(`/api/v1/policies/**`, `/api/v1/connections/**`, `/api/v1/platform/**` 등)는 위젯 scope로 **절대 접근 불가**. `@PreAuthorize` 미적용 = 위젯 토큰 기본 거부.

#### 3.1.8 SSE 쿼리 토큰 폴백
[backend/platform-core/src/main/java/com/platform/auth/JwtAuthenticationFilter.java](../backend/platform-core/src/main/java/com/platform/auth/JwtAuthenticationFilter.java)

```java
String bearer = request.getHeader("Authorization");
if (bearer == null || !bearer.startsWith("Bearer ")) {
    // SSE: EventSource는 헤더 설정 불가 → 쿼리 폴백
    String queryToken = request.getParameter("access_token");
    if (queryToken != null) {
        Claims c = parse(queryToken);
        if (!"widget".equals(c.get("type"))) {
            // access 토큰 쿼리 사용 금지 (로그 유출 리스크)
            response.setStatus(401);
            return;
        }
        // 이후 widget 분기 로직
    }
}
```

### 3.2 Phase 2 — RAG Citations 활성화

#### 3.2.1 `OrchestratorEngine.java:244` 교체
[backend/platform-core/src/main/java/com/platform/orchestrator/OrchestratorEngine.java:244](../backend/platform-core/src/main/java/com/platform/orchestrator/OrchestratorEngine.java#L244)

```java
// BEFORE
String context = ragService.buildContext(query, sourceId, topK);

// AFTER
RagContext ragCtx = ragService.buildContextWithCitations(query, sourceId, topK);
// ragCtx.contextText() + ragCtx.citations()
```

신규 DTO: `com.platform.rag.model.RagContext(String contextText, List<Citation> citations)`

#### 3.2.2 `RetrievedChunk` 필드 추가
[backend/platform-core/src/main/java/com/platform/rag/model/RetrievedChunk.java:5-10](../backend/platform-core/src/main/java/com/platform/rag/model/RetrievedChunk.java#L5-L10)

```java
public record RetrievedChunk(
    String chunkId,         // ← CR-058 신규
    String sourceId,
    String content,
    double score,
    Map<String, Object> metadata
) {}
```

임베딩 조회 쿼리도 `embeddings.id`를 select 리스트에 추가.

#### 3.2.3 `Citation` DTO
[backend/platform-core/src/main/java/com/platform/rag/model/Citation.java](../backend/platform-core/src/main/java/com/platform/rag/model/Citation.java)

```java
public record Citation(
    @JsonProperty("chunk_id")        String chunkId,
    @JsonProperty("source_id")       String sourceId,
    @JsonProperty("document_name")   String documentName,
    @JsonProperty("score")           double score,
    @JsonProperty("content_preview") String contentPreview,  // 200자 cap
    @JsonProperty("page_number")     Integer pageNumber,     // metadata에서 추출, 없으면 null
    @JsonProperty("metadata")        Map<String, Object> metadata
) {}
```

#### 3.2.4 `RAGService.buildContextWithCitations()` 개선
[backend/platform-core/src/main/java/com/platform/rag/RAGService.java:219](../backend/platform-core/src/main/java/com/platform/rag/RAGService.java#L219)

```java
public RagContext buildContextWithCitations(String query, String sourceId, int topK) {
    List<RetrievedChunk> chunks = retrieve(query, sourceId, topK);
    String sourceName = sourceRepo.findById(sourceId)
        .map(KnowledgeSource::getName).orElse("Unknown");

    List<Citation> citations = chunks.stream()
        .map(c -> new Citation(
            c.chunkId(),
            c.sourceId(),
            sourceName,
            c.score(),
            truncate(c.content(), 200),
            extractPageNumber(c.metadata()),
            c.metadata()
        ))
        .toList();

    String contextText = formatContextWithMarkers(chunks);  // [1] [2] 마커
    return new RagContext(contextText, citations);
}
```

#### 3.2.5 `ChatResponse` 필드 + SSE done 이벤트
[backend/platform-core/src/main/java/com/platform/orchestrator/ChatResponse.java](../backend/platform-core/src/main/java/com/platform/orchestrator/ChatResponse.java)

```java
public record ChatResponse(
    String id, String model, String sessionId,
    String content,
    List<ActionResult> actionsExecuted,
    Usage usage, BigDecimal costUsd,
    GuardrailResult guardrail,
    @JsonProperty("citations")  List<Citation> citations,    // ← 신규
    @JsonProperty("rag_used")   Boolean ragUsed              // ← 신규
) {}
```

`ChatController.java:156-161` done 이벤트:
```java
emitter.send(SseEmitter.event().name("done").data(Map.of(
    "done", true,
    "usage", usage,
    "rag_used", ragUsed,
    "citations", citations != null ? citations : List.of()
)));
```

#### 3.2.6 `GET /api/v1/knowledge-sources/{sourceId}/chunks/{chunkId}`
[backend/platform-core/src/main/java/com/platform/api/KnowledgeController.java](../backend/platform-core/src/main/java/com/platform/api/KnowledgeController.java)

```java
@GetMapping("/{sourceId}/chunks/{chunkId}")
@PreAuthorize("hasAuthority('SCOPE_rag:read') or authenticated")
public ApiResponse<ChunkDetail> getChunk(
    @PathVariable String sourceId,
    @PathVariable String chunkId
) {
    EmbeddingEntity e = embeddingRepo.findByIdAndSourceId(chunkId, sourceId)
        .orElseThrow(() -> new NotFoundException("Chunk not found"));

    // 테넌트 격리는 TenantContext의 DataSource 라우팅으로 자동 보장

    return ApiResponse.ok(new ChunkDetail(
        e.getId(), e.getSourceId(), e.getSourceName(),
        e.getContent(),
        extractPageNumber(e.getMetadata()),
        e.getMetadata(),
        e.getParentContent()  // Parent-Child RAG 사용 시
    ));
}
```

### 3.3 Phase 3 — 워크플로우 SSE + parent_run_id

#### 3.3.1 Flyway 마이그레이션
[backend/platform-core/src/main/resources/db/migration/tenant/V{next}__add_parent_run_to_workflow_runs.sql](../backend/platform-core/src/main/resources/db/migration/tenant/)

```sql
ALTER TABLE workflow_runs
    ADD COLUMN parent_run_id UUID NULL,
    ADD COLUMN parent_step_id VARCHAR(255) NULL;

CREATE INDEX idx_workflow_runs_parent_run_id ON workflow_runs(parent_run_id);

COMMENT ON COLUMN workflow_runs.parent_run_id IS
    'CR-058: 서브워크플로우 호출 시 부모 runId. NULL이면 최상위 실행.';
```

#### 3.3.2 `WorkflowRunEntity` 필드 추가
```java
@Column(name = "parent_run_id")
private UUID parentRunId;

@Column(name = "parent_step_id", length = 255)
private String parentStepId;
```

#### 3.3.3 `SubWorkflowStepExecutor` 수정
[backend/platform-core/src/main/java/com/platform/workflow/step/SubWorkflowStepExecutor.java:94-100](../backend/platform-core/src/main/java/com/platform/workflow/step/SubWorkflowStepExecutor.java#L94-L100)

```java
// BEFORE: 서브워크플로우 실행 결과를 부모 런에 merge만 함
Object result = engine.execute(subWorkflowId, childContext);

// AFTER: 별도 WorkflowRunEntity 생성 + parent 설정
WorkflowRunEntity childRun = new WorkflowRunEntity();
childRun.setWorkflowId(subWorkflowId);
childRun.setStatus("running");
childRun.setParentRunId(ctx.runId());
childRun.setParentStepId(step.getId());
childRun.setStartedAt(Instant.now());
workflowRunRepo.save(childRun);

try {
    Object result = engine.execute(subWorkflowId, childContext, childRun.getId());
    childRun.setStatus("completed");
    return result;
} catch (Exception e) {
    childRun.setStatus("failed");
    throw e;
} finally {
    childRun.setCompletedAt(Instant.now());
    workflowRunRepo.save(childRun);
}
```

#### 3.3.4 `WorkflowEventPublisher` 신규
[backend/platform-core/src/main/java/com/platform/workflow/event/WorkflowEventPublisher.java](../backend/platform-core/src/main/java/com/platform/workflow/event/WorkflowEventPublisher.java)

```java
@Component
public class WorkflowEventPublisher {
    private final ApplicationEventPublisher publisher;

    public void stepRunning(UUID runId, String stepId, UUID parentRunId) { ... }
    public void stepCompleted(UUID runId, String stepId, UUID parentRunId,
                              Instant startedAt, Instant completedAt,
                              String subWorkflowId, Map<String,Object> output) { ... }
    public void stepFailed(UUID runId, String stepId, UUID parentRunId,
                           Throwable error) { ... }
    public void approvalRequired(UUID runId, String stepId, String policyId,
                                 String reason, List<String> approvers,
                                 Instant timeoutAt) { ... }
    public void runCompleted(UUID runId, String status, long durationMs) { ... }
}
```

이벤트 클래스 3종:
- `StepStatusChangedEvent(runId, stepId, status, startedAt, completedAt, durationMs, parentRunId, subWorkflowId, error)`
- `ApprovalRequiredEvent(runId, stepId, policyId, reason, approvers, timeoutAt)`
- `RunCompletedEvent(runId, status, durationMs)`

#### 3.3.5 `WorkflowEngine` publish 주입

| 위치 | 전이 | publish |
|------|------|---------|
| [WorkflowEngine.java:288](../backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java#L288) | 스텝 시작 | `stepRunning(runId, stepId, parentRunId)` |
| [WorkflowEngine.java:299-306](../backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java#L299-L306) | 스텝 완료 | `stepCompleted(...)` |
| [WorkflowEngine.java:312-319](../backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java#L312-L319) | 스텝 실패 | `stepFailed(...)` |
| [WorkflowEngine.java:341-363](../backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java#L341-L363) | HUMAN_INPUT | `approvalRequired(...)` |
| [WorkflowEngine.java:323-328](../backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java#L323-L328) | 런 완료 | `runCompleted(...)` |

**Virtual Thread 전파**: `ChatController.streamResponse:122-173` 패턴 차용. Virtual Thread 진입 전에 `tenantId`, `SecurityContext`를 캡처 → 내부에서 `TenantContext.setCurrentTenantId(captured)` 재설정.

#### 3.3.6 `GET /api/v1/workflows/runs/{runId}/subscribe`
[backend/platform-core/src/main/java/com/platform/api/WorkflowController.java](../backend/platform-core/src/main/java/com/platform/api/WorkflowController.java)

```java
@GetMapping(value = "/runs/{runId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@PreAuthorize("hasAuthority('SCOPE_workflow:subscribe') or authenticated")
public SseEmitter subscribeRun(@PathVariable UUID runId) {
    // 런 존재 + 테넌트 일치 검증
    WorkflowRunEntity run = runRepo.findById(runId).orElseThrow(...);

    SseEmitter emitter = new SseEmitter(1_800_000L);  // 30분
    WorkflowRunSubscriber sub = new WorkflowRunSubscriber(runId, emitter);
    subscriberRegistry.register(sub);

    // 현재 상태 즉시 push (initial state)
    emitter.send(SseEmitter.event().name("workflow.snapshot").data(toSnapshot(run)));

    // heartbeat 15s
    scheduler.scheduleAtFixedRate(
        () -> { try { emitter.send(SseEmitter.event().comment("heartbeat")); } catch (IOException e) {} },
        15, 15, TimeUnit.SECONDS);

    emitter.onCompletion(() -> subscriberRegistry.unregister(sub));
    emitter.onTimeout(() -> subscriberRegistry.unregister(sub));
    return emitter;
}
```

**이벤트 리스너** (subscriberRegistry 내부):
```java
@EventListener
public void onStepStatus(StepStatusChangedEvent ev) {
    subscribers.stream()
        .filter(s -> s.matchesRun(ev.runId()) ||
                    s.matchesRun(ev.parentRunId()))  // 서브워크플로우 이벤트도 부모 구독자에게
        .forEach(s -> s.send("workflow.step", ev.toJson()));
}
```

#### 3.3.7 SSE 이벤트 포맷

```
event: workflow.snapshot
data: {"run_id":"...","status":"running","steps":[...]}

event: workflow.step
data: {"run_id":"...","step_id":"fetch_order","status":"running",
       "started_at":"2026-04-24T10:00:00Z","parent_run_id":null,"sub_workflow_id":null}

event: workflow.approval
data: {"run_id":"...","step_id":"confirm_refund","policy_id":"refund_over_100k",
       "reason":"10만원 초과","approvers":["manager@..."],"timeout_at":"2026-04-24T10:30:00Z"}

event: workflow.done
data: {"run_id":"...","status":"completed","duration_ms":3420}
```

---

## 4. 프론트 설계 — Sprint 53

### 4.1 모노레포 전환 (Phase 4 전제)

```
aimbase/
├── apps/
│   └── console/          ← 기존 frontend/ 이동
├── packages/
│   ├── chat-widget/      ← React 패키지 (ESM/CJS + .d.ts)
│   └── chat-widget-embed/← UMD + Web Component
├── pnpm-workspace.yaml
└── package.json          (workspace 루트)
```

### 4.2 `@aimbase/chat-widget` 공개 API

```ts
export interface AimbaseChatOptions {
  baseUrl: string;
  authResolver: () => Promise<{token: string; expiresAt: number}>;
  contextProvider?: () => Record<string, unknown>;
  display?: 'bubble' | 'inline' | 'panel';
  target?: string | HTMLElement;   // inline/panel일 때 마운트 타겟
  workflow?: {
    enabled?: boolean;                        // default true
    allowApproval?: boolean;                  // default false
    visualizationMode?: 'inline' | 'drawer';  // default 'inline'
  };
  rag?: {
    previewMode?: 'side-panel' | 'modal' | 'hidden';
    onCitationClick?: (c: Citation) => void;
  };
  theme?: {
    mode?: 'light' | 'dark' | 'auto';
    cssVars?: Record<string, string>;
  };
  on?: {
    onMessage?: (msg: ChatMessage) => void;
    onWorkflowStep?: (ev: WorkflowStepEvent) => void;
    onApprovalRequired?: (ev: ApprovalEvent) => void;
    onError?: (err: AimbaseError) => void;
    onTokenExpiring?: () => void;
  };
}

export function initAimbaseChat(options: AimbaseChatOptions): AimbaseChatHandle;

export interface AimbaseChatHandle {
  open(): void;
  close(): void;
  sendMessage(text: string, meta?: Record<string, unknown>): void;
  abort(): void;
  destroy(): void;
}

// React 컴포넌트
export const AimbaseChat: React.FC<AimbaseChatOptions>;
```

### 4.3 SSE 핸들러 구조

```
useChatStream(token, sessionId)
├─ fetch('/api/v1/chat/completions', {method: POST, stream: true})
├─ ReadableStream → EventSource 포맷 파싱
├─ 이벤트별 reducer:
│    delta        → append message content
│    thinking     → append thinking panel
│    tool_use_start → show tool card
│    tool_result  → update tool card
│    done         → finalize + render citations
└─ error 재연결 (최대 3회, exponential backoff)

useWorkflowStream(token, runId)
├─ new EventSource('/workflows/runs/{runId}/subscribe?access_token=...')
├─ 이벤트별 reducer:
│    workflow.snapshot → initialize tree
│    workflow.step     → update step status
│    workflow.approval → emit onApprovalRequired
│    workflow.done     → close stream
└─ 자동 재연결 (EventSource 기본 + 5s cap)
```

### 4.4 토큰 자동 갱신

```ts
function useTokenLifecycle(authResolver, onExpiring) {
  const [token, setToken] = useState<TokenState>();

  useEffect(() => {
    authResolver().then(setToken);
  }, []);

  useEffect(() => {
    if (!token) return;
    const refreshAt = token.expiresAt - 5 * 60 * 1000;  // exp - 5min
    const delay = Math.max(0, refreshAt - Date.now());
    const id = setTimeout(async () => {
      onExpiring?.();
      const fresh = await authResolver();
      setToken(fresh);
    }, delay);
    return () => clearTimeout(id);
  }, [token]);

  return token;
}
```

**진행 중 SSE 유지**: 토큰 갱신 시 기존 EventSource는 유지(연결은 초기 토큰으로 성립), 다음 새 메시지/런부터 신규 토큰 사용.

### 4.5 Web Component (Phase 5)

```html
<aimbase-chat
  base-url="https://aimbase.company.com"
  token-endpoint="/my-bff/aimbase-token"
  display="bubble"
  theme-mode="auto">
</aimbase-chat>
```

**구현**: `packages/chat-widget-embed/src/web-component.ts`
```ts
class AimbaseChatElement extends HTMLElement {
  connectedCallback() {
    const shadow = this.attachShadow({mode: 'open'});  // 스타일 격리
    const handle = initAimbaseChat({
      baseUrl: this.getAttribute('base-url')!,
      authResolver: () => fetch(this.getAttribute('token-endpoint')!).then(r => r.json()),
      display: (this.getAttribute('display') as any) ?? 'bubble',
      target: shadow,
      // ... 속성 → 옵션 매핑
    });
    this._handle = handle;
  }
  disconnectedCallback() { this._handle?.destroy(); }
}
customElements.define('aimbase-chat', AimbaseChatElement);
```

---

## 5. 보안 고려사항

| 위협 | 완화책 |
|------|--------|
| API Key 유출 | BFF 프록시 필수, 브라우저 번들 미노출 |
| 위젯 토큰 탈취 | TTL ≤ 1시간, origin claim 검증, scope 최소화 |
| SSE URL 토큰 노출 (액세스 로그) | 위젯 토큰(단기)만 쿼리 허용, access 토큰은 헤더 전용 |
| XSS (모델 출력 렌더) | `react-markdown` + `rehype-sanitize` 필수. HTML raw 금지 |
| CSRF | CORS `allowCredentials=false` + Bearer 토큰 방식 (쿠키 미사용) |
| Scope 누락 사고 | 관리 API는 `@PreAuthorize` 미적용 시 거부, 위젯 scope 화이트리스트만 허용 |
| 세션 하이재킹 | `conversation_sessions.user_ref` 저장 + 토큰 `sub`와 불일치 시 403 |
| 타 테넌트 청크 조회 | `TenantContext` 기반 DataSource 라우팅이 자동 차단 + `source_id` 소속 재확인 |

---

## 6. 테스트 설계

### 6.1 서버 단위 테스트 (Sprint 52)

| 파일 | 대상 | 케이스 |
|------|------|--------|
| `WidgetTokenControllerTest` | 토큰 발급 | TTL cap / scope 교집합 / origin 검증 / API Key 없으면 401 (5+) |
| `JwtAuthenticationFilterTest` | 위젯 분기 | type=widget 분기 / origin 불일치 403 / scope→authority 매핑 (4+) |
| `CorsConfigTest` | 동적 오리진 | 전역 허용 / 테넌트 허용 / 미허용 거부 (3+) |
| `RAGServiceTest` (보강) | citations | buildContextWithCitations 반환 검증 (3+) |
| `KnowledgeControllerTest` | 청크 조회 | 존재 / 테넌트 불일치 404 / 권한 없음 403 (3+) |
| `WorkflowEngineTest` (보강) | 이벤트 publish | 5개 전이 지점 각각 발행 검증 (5+) |
| `WorkflowControllerSseTest` | SSE 구독 | snapshot 발행 / step 이벤트 / 자식 런 이벤트 중계 / heartbeat (4+) |

### 6.2 통합 테스트

- TestContainers(Postgres 16 + Redis) + WireMock(LLM)
- 위젯 토큰 → Chat SSE → done citations 확인 (E2E curl 자동화)
- 서브워크플로우 포함 런 → subscribe → 자식 step 이벤트 수신 검증

### 6.3 E2E 9 시나리오 (Sprint 53)

**환경**: BE `localhost:8080`, 샘플 소비앱 `localhost:3999`

1. CORS 차단 — allowed_origins 미등록 → preflight 실패
2. CORS 통과 — `http://localhost:3999` 등록 후 성공
3. 토큰 발급 — BFF → API Key로 `issue-widget-token` → JWT claims 검증
4. Scope gate — `rag:read` 없는 토큰으로 `/chunks` 호출 → 403
5. 채팅 스트림 — 위젯 마운트 → delta 누적 → done에 citations
6. 토큰 만료 갱신 — TTL 30s dev → 25s 시점 onTokenExpiring → 스트림 끊김 없음
7. 워크플로우 승인 — HUMAN_INPUT 포함 런 → `workflow.approval` → 소비앱 자체 승인 → 재개 → `workflow.done`
8. RAG 출처 — citation 클릭 → side-panel → `/chunks/{chunkId}` 원문
9. abort — 스트림 중 `/abort` → 종료 확인

---

## 7. 배포 게이트 (T4 연계)

### Sprint 52 완료 기준
- [ ] 서버 단위 테스트 커버리지 ≥ 80% (신규 코드)
- [ ] curl E2E 5 시나리오 통과 (위젯 없이도)
- [ ] 기존 테스트 0 회귀
- [ ] Flyway 마이그레이션 롤백 SQL 준비 (tenant DB 각각)
- [ ] CR-036 API 가이드 `aimbase-api-guide.md` 위젯 섹션 추가
- [ ] 운용 가이드 `aimbase-ops-guide.md`에 `widget.allowed_origins` 관리 시나리오 추가

### Sprint 53 완료 기준
- [ ] React + Web Component 양쪽 번들 빌드 성공
- [ ] 번들 크기 gzip ≤ 120KB (목표), hard limit ≤ 200KB
- [ ] 샘플 소비앱에서 E2E 9 시나리오 전부 통과
- [ ] 소비앱 통합 가이드 완성 (Node/Spring/FastAPI BFF + React/Vue/Vanilla 샘플)

---

## 8. 위험과 완화

| 위험 | 완화책 |
|------|--------|
| CORS 설정 변경이 기존 API에 영향 | 적용 범위를 명시적 path 리스트로 제한 (`/chat/**` 등). admin API 제외 |
| Virtual Thread SecurityContext 누락 (CR-051 재발) | `ChatController:122-173` 패턴 100% 차용 + 통합 테스트로 회귀 방지 |
| 위젯 토큰 JWT가 기존 access 토큰과 섞임 | `type` claim 필수, 미존재 시 access로 간주 (하위 호환) |
| `parent_run_id` 컬럼 추가로 기존 데이터 NULL | 기존 런은 NULL = 최상위 런. 기존 코드 영향 없음 |
| 번들 크기 초과 | react-markdown 대안(`markdown-it`) 검토, tree-shaking 엄격 |
| 소비앱 BFF 구현 난이도 | 샘플 코드 3종(Node/Spring/FastAPI) + 통합 가이드 |

---

## 9. Sprint 52 구현 중 발견된 델타

설계서 원안과 실제 구현 사이의 차이 — 추후 CR 059 이후의 후속 작업이나 유지보수 시 참고.

### 9-1. S3-2 `SubWorkflowStepExecutor` child run 분리 생성 — **스킵**
- **원안**: 서브워크플로우 실행 시 `WorkflowRunEntity` 를 자식용으로 별도 생성하고 `parent_run_id`·`parent_step_id` 를 설정해 실존 트리로 저장.
- **실제 구현**: 기존 인라인 실행을 유지하고, `workflow.step` SSE 이벤트 payload 의 `sub_workflow_id` 로 자식 표시를 대체. DB 스키마(`parent_run_id` 컬럼) 는 정상 적용되어 후속 CR 에서 child run 생성으로 승격 가능한 여지 유지.
- **사유**: 자식 run 을 별도 레코드로 만들려면 SubWorkflowStepExecutor 가 WorkflowEngine 의 doExecuteAsync 경로를 재진입해야 하는데 현재 구조상 executor 레이어에서 엔진 재호출은 순환 의존을 유발. 별도 리팩토링이 필요.
- **영향**: 위젯 트리 UI 는 이벤트 기반으로 자식을 표시할 수 있으나, "자식 런 전용 stepResults 기록" 이나 "자식 런 개별 재시도" 는 불가. 필요해지면 후속 CR.

### 9-2. 요청 바디 snake_case 바인딩
- **원안**: Spring Jackson 기본 설정이 snake_case 를 지원할 것으로 가정.
- **실제**: Aimbase 는 전역 `PROPERTY_NAMING_STRATEGY=SNAKE_CASE` 설정이 없음. DTO 필드마다 `@JsonProperty` 명시 필요.
- **적용 위치**: `WidgetTokenController.IssueRequest` 4개 필드(`project_id`/`user_ref`/`session_hint`/`ttl_seconds`). 회귀 방지 테스트 `WidgetTokenControllerTest#issueRequest_bindsSnakeCaseFromJson` 추가.

### 9-3. CORS preflight 와 `TenantResolver`
- **원안**: `TenantResolver` @Order(-200) 는 CORS 필터(Spring CorsFilter) 보다 앞서 실행되지만 OPTIONS 메서드에 대한 특별 처리는 다루지 않았음.
- **실제**: OPTIONS 프리플라이트에는 커스텀 헤더(`X-Tenant-Id`) 가 포함되지 않는다. `TenantResolver` 가 400 을 반환하여 브라우저 CORS 검사가 CorsFilter 에 도달하기 전 차단됨.
- **수정**: `TenantResolver.doFilter()` 최상단에서 OPTIONS 요청을 즉시 체인 위임. 회귀 방지 테스트 `TenantResolverTest#doFilter_optionsRequest_shouldBypassTenantCheck` 2건 추가.

### 9-4. Tenant Flyway 자동 적용 경로 부재
- **관찰**: `db/migration/tenant/V54__*.sql` 은 기동 시점에 **기존 활성 테넌트에 자동으로 재실행되지 않는다**. `LocalDevInitializer` 는 `@Profile("local")` 이고, `TenantOnboardingService` 는 신규 테넌트 등록 시점만 호출.
- **영향**: CR-058 이후 배포 시 각 활성 테넌트 DB 에 수동 적용 필요. 운영 가이드 § 2-5 에 수동 절차 문서화.
- **후속**: 기동 시점 또는 Admin API 로 일괄 migrate 수단 도입은 별도 CR 후보 (CR-058 범위 외).

### 9-5. Scope 게이트 느슨함
- **원안**: `@PreAuthorize("hasAuthority('SCOPE_rag:read')")` 등 scope 필수.
- **실제**: `@PreAuthorize("hasAuthority('SCOPE_rag:read') or isAuthenticated()")` 로 기존 access 토큰/API Key 호출도 허용.
- **사유**: 기존 admin/도메인 유저의 같은 엔드포인트 사용을 막지 않기 위함. 위젯 토큰 발급 시점에 `grantedScopes.isEmpty()` 이면 400 이므로 "scope 없는 위젯 토큰" 은 존재 불가 → 보안 경계는 유지됨.
- **후속**: 위젯 전용 scope 강제를 원하면 `@PreAuthorize("hasAuthority('SCOPE_rag:read') or hasRole('USER') or hasRole('ADMIN')")` 로 명시화 가능. 현재는 기존 행동 호환 우선.

---

## 10. 후속 CR 후보 (범위 제외)

- **CR-059** Vue/Svelte 전용 래퍼 (필요 시)
- **CR-060** 위젯 음성 입력 UI (STT 인프라 연동)
- **CR-061** 위젯 파일 업로드 (이미지/PDF)
- **CR-062** 위젯 CDN 배포 파이프라인 (사내 S3 또는 공개 npm)
- **CR-TBD** SubWorkflow 자식 run 분리 생성 (§ 9-1 승격)
- **CR-TBD** Tenant Flyway 자동 재마이그레이션 (§ 9-4 해소)

---

## 참고 파일

**서버 변경**:
- [backend/platform-core/src/main/java/com/platform/config/SecurityConfig.java](../backend/platform-core/src/main/java/com/platform/config/SecurityConfig.java)
- [backend/platform-core/src/main/java/com/platform/auth/JwtProvider.java](../backend/platform-core/src/main/java/com/platform/auth/JwtProvider.java)
- [backend/platform-core/src/main/java/com/platform/auth/JwtAuthenticationFilter.java](../backend/platform-core/src/main/java/com/platform/auth/JwtAuthenticationFilter.java)
- [backend/platform-core/src/main/java/com/platform/api/ChatController.java](../backend/platform-core/src/main/java/com/platform/api/ChatController.java)
- [backend/platform-core/src/main/java/com/platform/orchestrator/OrchestratorEngine.java](../backend/platform-core/src/main/java/com/platform/orchestrator/OrchestratorEngine.java)
- [backend/platform-core/src/main/java/com/platform/orchestrator/ChatResponse.java](../backend/platform-core/src/main/java/com/platform/orchestrator/ChatResponse.java)
- [backend/platform-core/src/main/java/com/platform/rag/RAGService.java](../backend/platform-core/src/main/java/com/platform/rag/RAGService.java)
- [backend/platform-core/src/main/java/com/platform/rag/model/RetrievedChunk.java](../backend/platform-core/src/main/java/com/platform/rag/model/RetrievedChunk.java)
- [backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java](../backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java)
- [backend/platform-core/src/main/java/com/platform/workflow/step/SubWorkflowStepExecutor.java](../backend/platform-core/src/main/java/com/platform/workflow/step/SubWorkflowStepExecutor.java)
- [backend/platform-core/src/main/java/com/platform/api/WorkflowController.java](../backend/platform-core/src/main/java/com/platform/api/WorkflowController.java)
- [backend/platform-core/src/main/java/com/platform/api/KnowledgeController.java](../backend/platform-core/src/main/java/com/platform/api/KnowledgeController.java)

**프론트 신규**:
- `packages/chat-widget/src/` (신규 패키지)
- `packages/chat-widget-embed/src/` (신규 패키지)
- `tools/sample-consumer-app/` (신규 샘플)

**연관 설계서**:
- [T3-1_데이터_모델.md](T3-1_데이터_모델.md) — `workflow_runs` 컬럼 추가 반영 필요
- [T3-2_API_설계.md](T3-2_API_설계.md) — 신규 엔드포인트 3종 반영 필요
- [T3-7_CR-055_EvaluatorOptimizer_설계서.md](T3-7_CR-055_EvaluatorOptimizer_설계서.md) — 같은 T3 계열 형식 참고
