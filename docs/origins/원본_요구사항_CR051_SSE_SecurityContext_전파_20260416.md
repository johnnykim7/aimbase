# 원본 요구사항 — SSE 스트림 종료 시 Spring Security AccessDenied 해소

- **대상 CR**: CR-051
- **발견일**: 2026-04-16
- **발견 경위**: CR-045 Phase 2-B E2E 검증 중 로그에서 확인
- **영향**: 기능 동작에는 영향 없음 (SSE 이벤트 정상 전달, 스트림 정상 완료). 그러나 매 스트리밍 요청마다 BE 로그에 `AuthorizationDeniedException` 스택이 2회 남음 — 운영상 노이즈 + 보안 관측성 착시.

## 1. 재현 시나리오

```
POST /api/v1/chat/completions
  Authorization: Bearer <JWT>
  X-Tenant-Id: tenant_dev
  { "stream": true, "actions_enabled": true, ... }
```

스트림이 정상 전송되고 클라이언트는 `event:delta` → `event:done`을 모두 수신. 그러나 서버 로그:

```
ERROR ... [omcat-handler-4] o.a.c.c.C.[.[.[/].[dispatcherServlet]
  Servlet.service() for servlet [dispatcherServlet] threw exception
  org.springframework.security.authorization.AuthorizationDeniedException: Access Denied
  at org.springframework.security.web.access.intercept.AuthorizationFilter.doFilter(AuthorizationFilter.java:99)
  ...

ERROR ... s.e.ErrorMvcAutoConfiguration$StaticView
  Cannot render error page for request [null] as the response has already been committed.
```

## 2. 원인 분석

1. `ChatController.streamResponse()`가 `SseEmitter`를 반환 → Spring MVC가 **async 모드**로 전환 (Tomcat async dispatch)
2. 실제 스트림 작성은 `Thread.ofVirtual().start(...)` 안에서 진행됨
3. 가상 스레드에는 `SecurityContextHolder`의 `SecurityContext`(ThreadLocal)가 **전파되지 않음**
4. 스트림 완료 후 Spring이 요청을 async dispatch로 재처리하는 과정에서 `AuthorizationFilter`가 **익명 사용자**로 판단 → `Access Denied`
5. 이미 응답은 SSE로 커밋된 상태 → 에러 페이지 렌더 실패 로그 추가 발생

CR-045에서 비슷한 패턴으로 `TenantContext`는 수동 전파했지만, `SecurityContextHolder`는 그대로 둠.

## 3. 해결 방향

### 3.1 코드 수정
`ChatController.streamResponse()`의 `Thread.ofVirtual().start(...)` 진입 전에 **현재 SecurityContext를 캡처해 가상 스레드에서 재주입**.

```java
// 의사 코드
final var securityContext = SecurityContextHolder.getContext();
final var propagatedTenantId = TenantContext.getTenantId();
Thread.ofVirtual().start(() -> {
    SecurityContextHolder.setContext(securityContext);
    if (propagatedTenantId != null) TenantContext.setTenantId(propagatedTenantId);
    try {
        // 기존 스트림 로직
    } finally {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }
});
```

Spring Security가 제공하는 `DelegatingSecurityContextRunnable` 사용도 가능:
```java
Runnable wrapped = new DelegatingSecurityContextRunnable(() -> { ... });
Thread.ofVirtual().start(wrapped);
```

### 3.2 검토 항목
- `SessionStore.persistToDb`, `AnthropicAdapter.chatStream`, `OpenAIAdapter.chatStream` 등 다른 가상 스레드 진입점에도 `SecurityContext` 전파 필요 여부
- Spring `ConcurrentTaskDecorator` + `TaskDecorator`로 전역화하는 설계 비교
- Extended Thinking 등 긴 스트림에서 SecurityContext 만료 관리 (현재는 JWT 이미 검증된 상태라 문제 없을 듯)

## 4. 스코프 추정

- BE 수정: ~3~5 파일, ~50 LOC
- 단위 테스트: SSE 종료 후 BE 로그에 AccessDenied 미발생 확인 (통합 테스트)
- 난이도: 중 (SecurityContextHolder 전략 이해 + 가상 스레드 라이프사이클 합의 필요)
- **CR-045와 분리** 이유: CR-045는 기능 완성이 목표였고 본 이슈는 관측성/정합성 이슈. 별도 커밋으로 리뷰 명확화.

## 5. 관련 커밋 / 참조
- CR-045 커밋 `a19b7e2` — 동일 패턴의 `TenantContext` 전파 수정 선례
- [ChatController.java:102-146](../../backend/platform-core/src/main/java/com/platform/api/ChatController.java#L102-L146)
- [SecurityConfig.java:54-82](../../backend/platform-core/src/main/java/com/platform/config/SecurityConfig.java#L54-L82)

## 6. 우선순위
**Medium** — 기능 무영향이지만 운영 로그 노이즈 제거 + 보안 필터 정합성 회복 가치. 벤치마크 실험 전에 해소하면 로그 판독 용이.
