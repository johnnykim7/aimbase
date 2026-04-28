# T3-13 CR-072 서버 도구 MCP Endpoint 노출 설계서

- **CR**: CR-072 — 서버 도구 32개를 MCP 서버 형태로 외부 노출
- **적용 버전**: v8.7.0
- **작성일**: 2026-04-27
- **상태**: 📝 설계 작성 (검토 대기)
- **원본 요구사항**: `docs/origins/원본_요구사항_CR072_ServerMcpEndpoint_20260427.md` (예정)
- **관련 CR**: CR-041 (Agent Registry), CR-042 (aimbase-agent MCP stdio), CR-044 (CLI 두뇌 + Aimbase 손발), CR-050 (ClaudeCli LLM 어댑터), CR-069 (ToolMode), CR-071 (ClaudeCliAdapter 3경로 통일)

---

## 1. 개요

### 1.1 목표

Aimbase 서버에 등록된 도구 32개 중 CLI 노출 대상 24개를 **MCP 서버 형태로 노출**하여, Claude CLI 가 사용자 PC `aimbase-agent` (SDK 14개) 외에 **서버 도구**도 같은 채널 패러다임으로 호출 가능하게 한다.

### 1.2 핵심 통찰

> **"모든 도구는 도구다. 어차피 MCP 서버 형태로 외부에 공개되어야 한다."** (사용자 발언, 2026-04-27)

- aimbase-agent SDK 도구 14개 — 이미 MCP 서버로 노출 ✓
- platform-core 서버 도구 32개 — 노출 안 됨 ❌ (in-process 호출만 가능)
- → **이 비대칭이 부자연스럽다.** CR-072 는 "신규 기능" 이 아니라 **빠진 일관성 회복**.

### 1.3 8개 키워드 결정 사항 (확정)

| # | 키워드 | 결정 |
|---|---|---|
| 1 | MCP 서버 구현 | `mcp-spring-webmvc` 0.10.0 재사용 (sdk-mcp 변환 유틸 공유) |
| 2 | 인증 | `X-API-Key` 만 (tenant 자동 결정). 기존 `ApiKeyAuthenticationFilter` 재사용. `/mcp/**` permitAll() 제거 |
| 3 | 화이트리스트 | `ToolContractMeta.mcpExposureLevel` enum (NONE/CLI/EXTERNAL). 기본 NONE. 24개 CLI / 8개 NONE |
| 4 | ToolMode 와 관계 | 직교. `mcp.server-exposure.enabled` 플래그로 on/off |
| 5 | 이름 충돌 | prefix 자동 분리(`mcp__aimbase-server__*`). 충돌 감지 로깅만 |
| 6 | STUN/TURN | 범위 외 (서버 공인 IP 가정) |
| 7 | SUB_WORKFLOW vs CLI MCP | 의도 다름 — 통합 X, 병존 |
| 8 | 거버넌스 | 정책 / 감사 / 권한 / RateLimit 적용. max_iter / Hook 풀세트는 포기 (CR-050 트레이드오프 계승) |

---

## 2. 아키텍처

### 2.1 As-Is

```
[Claude CLI]
   │  --mcp-config { "mcpServers": { "aimbase": { stdio: aimbase-agent.jar } } }
   ↓
[aimbase-agent --mcp-stdio]   ← MCP 서버 #1 (SDK 14개)
   └─ 사용자 PC 자원: FileRead, Bash, Glob, Grep, ...

[platform-core 서버]
   └─ ToolRegistry (32개 도구)  ← 노출 endpoint 없음. OrchestratorEngine 만 in-process 호출
```

**문제점**:
- 서버 도구를 외부에서 호출할 채널이 없음
- CLI 가 두뇌로 동작할 때(CR-050/CR-071) 서버 도구를 직접 사용 불가
- 도구 노출 정책의 비대칭

### 2.2 To-Be

```
[Claude CLI]
   │  --mcp-config {
   │    "mcpServers": {
   │      "aimbase-local":  { stdio: aimbase-agent.jar --mcp-stdio },
   │      "aimbase-server": { url: https://.../mcp/sse, headers: { X-API-Key, X-Aimbase-Agent-Id } }
   │    }
   │  }
   ↓
   ├─→ [aimbase-agent --mcp-stdio]                     ← MCP 서버 #1 (SDK 14개)
   │     └─ FileRead, Bash, Glob, Grep, ...
   │
   └─→ [platform-core /mcp/sse]                        ← MCP 서버 #2 (서버 도구 24개) ★ 신규
         │ SecurityFilterChain
         │   - TenantResolver (Order -200)
         │   - ApiKeyAuthenticationFilter   ← X-API-Key → tenant_id 자동
         │   - AgentIdRequestFilter         ← X-Aimbase-Agent-Id → RequestContext
         ↓
         ServerMcpController (mcp-spring-webmvc)
            └─ ServerMcpToolAdapter
                 └─ ToolRegistry.getToolsForMcpExposure(CLI, ctx)
                     └─ 24개 도구 → MCP Tool 변환 (UnifiedToolDef → McpSchema.Tool)
```

### 2.3 도구 분류

**CLI 노출 (24개)** — `mcpExposureLevel=CLI`:

| 카테고리 | 도구 |
|---|---|
| Network (2) | WebSearch, HttpRequest |
| Collaboration (2) | SendMessage, Notification |
| AI/Content (4) | Brief, ImageAnalysis, Translation, SuggestBackgroundPR |
| File/Result (2) | NotebookEdit, ReadToolResult |
| CLI/LSP (2) | LSP, SkillInvoke |
| Discovery (1) | ToolSearch |
| MCP 메타 (3) | ListMcpResources, ReadMcpResource, RemoteTrigger |
| Cron (3) | ScheduleCron, CronList, CronDelete |
| Task (6) | TaskCreate, TaskGet, TaskList, TaskUpdate, TaskOutput, TaskStop |
| Other (1) | TodoWrite |

**노출 제외 (8개)** — `mcpExposureLevel=NONE`:

| 카테고리 | 도구 | 이유 |
|---|---|---|
| Admin (2) | TeamCreate, TeamDelete | 관리 권한, CLI 내 의도 외 |
| Plan (3) | EnterPlanMode, ExitPlanMode, VerifyPlanExecution | 서버 OrchestratorEngine 전용 |
| Maintenance (1) | TempCleanup | 서버 운영 도구 |
| Self-ref (1) | AimbaseMcpConfigGenerator | 자기 자신 (의미 없음) |
| 기타 (1) | (예비) | 분류 후 확정 |

---

## 3. 컴포넌트 상세

### 3.1 신규 클래스

#### `ServerMcpController` (platform-core)

```
위치: backend/platform-core/src/main/java/com/platform/api/mcp/ServerMcpController.java
역할: /mcp/sse, /mcp/message endpoint 제공. Spring AI MCP 서버 빌더로 ToolRegistry 어댑터 연결.
의존:
  - ToolRegistry
  - ServerMcpToolAdapter
  - mcp-spring-webmvc (WebMvcSseServerTransportProvider)
  - PolicyEngine
  - AuditLogService
```

#### `ServerMcpToolAdapter` (platform-core)

```
위치: backend/platform-core/src/main/java/com/platform/mcp/server/ServerMcpToolAdapter.java
역할:
  - ToolRegistry 의 24개 도구 → McpSchema.Tool 변환
  - tools/list 핸들러: getToolsForMcpExposure(CLI, ToolContext) 반환
  - tools/call 핸들러:
    1. PolicyEngine.evaluate(toolName, ctx, params) — DENY 시 MCP 에러
    2. ToolExecutor.execute(params, ctx) 호출
    3. ToolResultRenderer.render() + McpResultTruncator (sdk-mcp 재사용)
    4. AuditLogService 기록
의존: ToolResultRenderer, McpResultTruncator (sdk-mcp 모듈에서 공유)
```

#### `ToolContractMeta.mcpExposureLevel` (sdk-tool-core)

```
위치: backend/sdk/tool-sdk-core/src/main/java/com/platform/tool/McpExposureLevel.java
enum 값:
  - NONE: MCP 노출 안 함 (기본)
  - CLI: CLI 두뇌가 호출 가능
  - EXTERNAL: 외부 시스템도 호출 가능 (별도 인증 정책 — CR-072 범위 외)

ToolContractMeta record 에 필드 추가:
  McpExposureLevel mcpExposureLevel() default McpExposureLevel.NONE
```

#### `ToolRegistry.getToolsForMcpExposure` (platform-core)

```java
public List<UnifiedToolDef> getToolsForMcpExposure(McpExposureLevel level, ToolContext ctx) {
    return registered.stream()
        .filter(e -> e instanceof EnhancedToolExecutor enhanced
                  && enhanced.getContractMeta().mcpExposureLevel() == level)
        .filter(e -> hasPermission(e, ctx))   // 권한 레벨 체크
        .map(ToolExecutor::getDefinition)
        .toList();
}
```

### 3.2 수정 클래스

#### `SecurityConfig`

```java
// 변경 전:
.requestMatchers("/mcp/**").permitAll()

// 변경 후:
.requestMatchers("/mcp/**").authenticated()
// + ApiKeyAuthenticationFilter, AgentIdRequestFilter 가 /mcp/** 도 처리하도록 매칭 확장
```

#### `AimbaseMcpConfigGenerator` (platform-core)

```java
// 변경 전: 단일 mcpServers
{
  "mcpServers": {
    "aimbase": { "command": "java", "args": [...] }
  }
}

// 변경 후: 다중 mcpServers
{
  "mcpServers": {
    "aimbase-local": {
      "command": "java",
      "args": ["-jar", "${jarPath}", "--mcp-stdio"]
    },
    "aimbase-server": {
      "url": "${aimbase.server.base-url}/mcp/sse",
      "headers": {
        "X-API-Key": "${apiKey}",
        "X-Aimbase-Agent-Id": "${agentId}"
      }
    }
  }
}
```

호출 사이트 변경:
- `ClaudeCliAdapterConfig.resolveMcpConfigJson()` 가 server-exposure.enabled 시 두 키 모두 출력

---

## 4. 시퀀스

### 4.1 CLI 시동 → 도구 디스커버리

```
[Claude CLI 시동]
  │
  ├─ --mcp-config 읽기 → mcpServers 2개 인식
  │
  ├─→ aimbase-local: 자식 프로세스 spawn (java -jar ... --mcp-stdio)
  │     └─ MCP initialize → tools/list → 14개 도구 응답
  │
  └─→ aimbase-server: HTTPS connect (https://.../mcp/sse, headers 포함)
        │ [SecurityFilterChain]
        │   - TenantResolver (헤더 기반 OR ApiKey 위임)
        │   - ApiKeyAuthenticationFilter: X-API-Key SHA-256 → tenant_id → TenantContext
        │   - AgentIdRequestFilter: X-Aimbase-Agent-Id → RequestContext
        ↓
        ServerMcpController
          ↓ MCP initialize
          ↓ MCP tools/list
            → ServerMcpToolAdapter.list(CLI, currentToolContext())
              → 24개 UnifiedToolDef → McpSchema.Tool 변환 → 응답
              
[CLI 입장]
  통합 도구 카탈로그: 14 + 24 = 38 (prefix 분리: mcp__aimbase-local__*, mcp__aimbase-server__*)
```

### 4.2 도구 호출 (예: WebSearch)

```
[모델이 WebSearch 호출 결정]
  │
[Claude CLI]
  │ MCP tools/call { "name": "mcp__aimbase-server__web_search", "arguments": {...} }
  ↓ HTTPS POST /mcp/message (with X-API-Key, X-Aimbase-Agent-Id)
[ServerMcpController]
  ↓ tools/call 핸들러
[ServerMcpToolAdapter]
  ↓ 1. PolicyEngine.evaluate("WebSearch", ctx, params) → ALLOW
  ↓ 2. ToolRegistry.get("WebSearch").execute(params, ctx)
      ↓ 실제 도구 실행 (외부 검색 API 호출 등)
      ↓ ToolResult 반환
  ↓ 3. ToolResultRenderer.render(result) → 본문 + 메타
  ↓ 4. McpResultTruncator.truncate (8K)
  ↓ 5. AuditLogService.log(toolName, ctx, success, duration)
  ↓
[ServerMcpController]
  ↓ MCP tools/call response (JSON-RPC)
[Claude CLI]
  ↓ 모델에 도구 결과 전달
```

### 4.3 정책 거부 (예: DOMAIN_FILTER 정책에 막힌 HttpRequest)

```
[ServerMcpToolAdapter]
  ↓ PolicyEngine.evaluate → DENY (블랙리스트 도메인)
  ↓ MCP tools/call response: { "isError": true, "content": [{ "type": "text", "text": "Policy DENY: domain blocked" }] }
[Claude CLI / 모델]
  ↓ 에러 인지하여 다른 도구 / 다른 입력으로 재시도
```

---

## 5. 거버넌스 (CR-050 트레이드오프 계승)

| 정책 | 적용 |
|---|---|
| PolicyEngine 평가 | ✅ 적용 (도구 호출 사이트가 서버라 가능) |
| 감사 로깅 | ✅ 적용 (audit_log 동일 채널) |
| 권한 레벨 (READ_ONLY 등) | ✅ 적용 (ToolContext.permissionLevel 채워서 ToolRegistry 필터) |
| Rate Limit / Quota | ✅ 적용 (`mcp.rate-limit.requests-per-minute` — 테넌트 단위 Redis) |
| Approval Required | ⚠️ 부분 (도구가 approvalRequired=true 면 MCP 에러 반환 → CLI 가 사용자에게 노출) |
| Hook PRE/POST_TOOL_USE | ⚠️ 부분 (서버 측 도구 호출 사이트에서만. CLI 측 Hook 은 못 봄) |
| max_iterations | ❌ 적용 불가 (CLI 가 도구 루프 자율 통제) |
| SESSION_END 등 풀세트 Hook | ❌ 적용 불가 (CLI 측 이벤트) |

---

## 6. 보안

### 6.1 인증 흐름

```
[Claude CLI]  HTTPS request → /mcp/sse or /mcp/message
   headers: X-API-Key, X-Aimbase-Agent-Id

[Spring SecurityFilterChain]
   1. TenantResolver (-200): X-Tenant-Id 우선, 없으면 ApiKey 위임
   2. ApiKeyAuthenticationFilter:
        - X-API-Key SHA-256 → api_keys 테이블 조회
        - 만료 검증 + last_used_at 갱신
        - tenant_id → TenantContext
        - 실패 시 401
   3. AgentIdRequestFilter (+50):
        - X-Aimbase-Agent-Id → RequestContext (ThreadLocal)
        - 누락 시 즉시 거부 안 함 (도구가 필요할 때 requireAgentId() 호출)
   4. authenticated() 체크 통과
   
[ServerMcpController]  TenantContext 적용된 상태로 도구 실행
```

### 6.2 화이트리스트 강제

- `getToolsForMcpExposure(CLI, ctx)` 만 노출 → **NONE 도구는 tools/list 에 안 나타남**
- 모델이 NONE 도구 이름을 알아도 **tools/call 시 ServerMcpToolAdapter 가 거부** (이중 방어)

### 6.3 Rate Limit

- Redis 기반 sliding window
- 키: `mcp-rate:{tenant_id}:{agent_id}`
- 기본: 60 req/min (설정값 `mcp.rate-limit.requests-per-minute`)

---

## 7. 설정 (application.yml)

```yaml
mcp:
  server-exposure:
    enabled: true                    # CR-072 endpoint on/off
    base-url: https://aimbase.example.com   # CLI 가 connect 할 외부 base URL
  rate-limit:
    requests-per-minute: 60
    window-seconds: 60

aimbase:
  cli:
    mcp-config:
      include-server: true           # AimbaseMcpConfigGenerator 가 aimbase-server 항목 박을지
```

---

## 8. Phase 분할

| Phase | 범위 | 산출물 | 단위 테스트 |
|---|---|---|---|
| **Phase 1** | `McpExposureLevel` enum + ToolContractMeta 필드 추가 + 32개 도구 분류 적용 | sdk-tool-core, builtin tools | 도구별 메타 검증 (단위) |
| **Phase 2** | `ServerMcpToolAdapter` (UnifiedToolDef → McpSchema.Tool 변환, sdk-mcp 유틸 공유) + `ToolRegistry.getToolsForMcpExposure` | platform-core, sdk-mcp 변환 유틸 분리 | 변환 유틸 단위 8 PASS |
| **Phase 3** | `ServerMcpController` (`mcp-spring-webmvc` 통합) + 인증 필터 매칭 확장 + Rate Limit | platform-core, SecurityConfig | MCP 핸들러 단위 + 인증 필터 통합 |
| **Phase 4** | `AimbaseMcpConfigGenerator` 다중 mcpServers 출력 + 호출 사이트 갱신 (ClaudeCliAdapterConfig) | platform-core | Generator 단위 + 호출 사이트 회귀 |
| **Phase 5** | 거버넌스 (PolicyEngine + AuditLog + Hook PRE/POST_TOOL_USE) 통합 | ServerMcpToolAdapter 보강 | DENY 시 MCP 에러, 감사 기록 |
| **Phase 6** | 통합 테스트 + 가이드 문서 갱신 | api-guide v2.9.0, claudecode-mcp-setup v?, ops 가이드 | Claude Code 실 사용 + 회귀 |

**예상 MD**: 5 ~ 6 MD
**시동 순서**: 1 → 2 → 3 → 4 → 5 → 6 (의존 관계상 직렬)

---

## 9. 영향 범위 / 회귀 우려

| 모듈 | 변경 | 회귀 우려 |
|---|---|---|
| `ToolContractMeta` | 필드 1개 추가 | 기본값 NONE → 기존 도구 영향 없음 |
| `ToolRegistry` | 메서드 1개 추가 | 기존 메서드 변경 없음 |
| `SecurityConfig` | `/mcp/**` permitAll → authenticated | **AgentMcpServer 측은 별도 컨텍스트라 무관** (자기 Spring Boot 띄움) |
| `AimbaseMcpConfigGenerator` | 출력 JSON 구조 확장 | 호출 사이트(ClaudeCliAdapterConfig) 동시 갱신 필요 |
| `ServerMcpController` | 신규 | 신규라 회귀 없음 |
| `application.yml` | 키 3개 추가 | 기본값 합리적이면 영향 없음 |

**잠재 리스크**:
- mcp-spring-webmvc 가 platform-core 의 기존 Spring Web 컨텍스트와 충돌하지 않는지 검증 필요 (Phase 3)
- ApiKey 권한 스코프가 MCP endpoint 까지 자동 허용되는지 — RBAC 정책 점검 필요

---

## 10. 범위 외 (별도 CR)

- **CR-073 (예정)**: aimbase-agent 의 Spring Boot 인스턴스 통합 + `--runner-mode` 제거 (CR-072 와 직교)
- **CR-074 (예정)**: 다른 CLI(Codex/Gemini) 의 MCP 클라이언트 호환성 검증
- **TURN/STUN 경유 MCP SSE**: 서버는 공인 IP 가정, 폐쇄망은 별도 CR
- **EXTERNAL 노출 레벨**: CLI 외 외부 시스템(다른 IDE 등) 노출은 별도 정책 / 별도 인증

---

## 11. 완료 기준

- [ ] T3-13 설계서 (이 문서)
- [ ] `McpExposureLevel` enum + ToolContractMeta 필드 + 32개 분류
- [ ] `ServerMcpController` + `ServerMcpToolAdapter` + Rate Limit
- [ ] `AimbaseMcpConfigGenerator` 다중 mcpServers 출력
- [ ] PolicyEngine + AuditLog + Hook PRE/POST_TOOL_USE 통합
- [ ] 단위 테스트 (변환 / 인증 / 정책 / Rate Limit)
- [ ] 통합 테스트 (Claude Code 실 사용 — 서버 도구 24개 호출)
- [ ] 가이드 갱신: `aimbase-api-guide.md`, `claudecode-mcp-setup.md`, `aimbase-ops-guide.md`
- [ ] CR_변경_이력.md 상태 업데이트 (📝 발번 → ✅ 구현 완료)

---

## 12. 참고

- 기존 자산: `AgentMcpServer` (sdk-mcp), `ToolResultRenderer`, `McpResultTruncator`, `MCPServerClient`
- 라이브러리: `io.modelcontextprotocol.sdk:mcp-bom:0.10.0` + `mcp-spring-webmvc`
- 정찰 결과: 메모리 노트 `project_cr072_server_mcp_endpoint.md`
