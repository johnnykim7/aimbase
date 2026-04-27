# T3-12 CR-071 ClaudeCliAdapter 설계서

- **CR**: CR-071 ClaudeCliAdapter — 3경로 통일 (API / CLI어댑터 / ClaudeCodeTool)
- **적용 버전**: v8.6.0
- **작성일**: 2026-04-27
- **상태**: 📝 설계 작성 (검토 대기)
- **원본 요구사항**: `docs/origins/원본_요구사항_CR071_ClaudeCliAdapter_3경로통일_20260427.md`
- **관련 CR**: CR-041 (Agent Registry), CR-042 (aimbase-agent), CR-044 (CLI 두뇌 + Aimbase 손발), CR-050 (ClaudeCliLlmAdapter), CR-067 (ToolResultRenderer), CR-068 (어댑터 동등화), CR-069 (CommandBuilder + ToolMode), CR-070 (ClaudeCodeTool 스트리밍)

---

## 1. 개요

### 1.1 목표

Aimbase에 분산되어 있는 **Claude 호출 3경로**를 **LLMAdapter 단일 인터페이스**로 통일하고, CLI 실행 위치를 `connection` 단위로 자유 배치한다.

### 1.2 명명 체계

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| `ClaudeCliAdapter` | Aimbase 서버 in-process | LLMAdapter 구현체. Runner를 HTTP로 호출 |
| `ClaudeCliRunner` | 별도 프로세스 (서버 또는 사용자 PC `aimbase-agent`) | HTTP 서비스. 안에서 Worker로 CLI 실행 |
| `ClaudeCliWorker` (CR-050 기존) | Runner 내부 | CLI 자식 프로세스 1개를 다루는 워커 |
| `ClaudeCliCommandBuilder` (CR-069 기존) | Runner 내부 | CLI 명령 빌더 (ToolMode 스위치 포함) |

API/CLI 어댑터 대칭: `AnthropicAdapter`(REST API) vs `ClaudeCliAdapter`(CLI). Codex/Gemini 등은 향후 별도 어댑터로 추가.

> ⚠️ **용어 주의** — `ClaudeCliLlmAdapter`(CR-050 기존)와 `ClaudeCodeTool`(CR-044 기존)은 **이번 CR에서 즉시 삭제**한다(아직 운영 중이 아님). 단계적 deprecation 없이 단일 경로로 전환.
> - **삭제**: `ClaudeCliLlmAdapter`, `ClaudeCodeTool` 및 관련 클래스/테스트
> - **신규**: `ClaudeCliAdapter` 단독 경로
> - **이동**: `ClaudeCliWorker/Pool/CommandBuilder` → Runner 모듈

### 1.3 결정 사항 (사용자 확정)

| 보류 | 결정 |
|---|---|
| 인증 | `X-Api-Key` 헤더 (FlowGuard/Aimbase 기존 패턴 재활용) |
| NAT 통과 | 기존 STUN/TURN + AgentRegistry 인프라 재활용 (CR-041/CR-042) |
| 라우팅 | `X-Aimbase-Agent-Id` 요청 헤더 필수. 누락 시 400 에러. 자동화는 후속 CR |
| 스트리밍 | 기존 SSE + STREAM_SINK + NDJSON 자산 재활용 |

---

## 2. 아키텍처

### 2.1 As-Is (3경로 분산)

```
[브라우저/소비자앱]
       ↓
[Aimbase 서버 (OrchestratorEngine)]
       ├─ (1) AnthropicAdapter ────────────→ Anthropic REST API
       ├─ (2) ClaudeCliLlmAdapter ─────────→ in-process ClaudeCliWorker → claude CLI
       └─ (3) LLM_CALL → ClaudeCodeTool ──→ in-process Process spawn → claude CLI
                                            (또는 사용자 PC aimbase-agent를 도구로 위임)
```

**문제점:**
- 인터페이스 3개 → 거버넌스(정책/Hook/max_iter/감사) 적용 범위 분산
- (2)는 서버 in-process CLI → BIZ-099 ToS 우려
- (3)은 "도구로 LLM 위임" 안티패턴

### 2.2 To-Be (단일 LLMAdapter)

```
[브라우저/소비자앱]
       │  X-Api-Key, X-Aimbase-Agent-Id
       ↓
[Aimbase 서버 (OrchestratorEngine)]
       ├─ AnthropicAdapter ────────────────→ Anthropic REST API
       └─ ClaudeCliAdapter ─────────────────┐
              │ (X-Aimbase-Agent-Id 헤더)    │
              ↓                              │
       [AgentRegistry + RemoteAgentToolExecutor] (CR-041/042 기존)
              │                              │
              ↓                              │ (NDJSON 역방향 채널)
       [ClaudeCliRunner — aimbase-agent 내부]│
              │                              │
              ├─ ClaudeCliWorker (이동)      │
              ├─ ClaudeCliCommandBuilder (이동)
              ↓
       [claude CLI (--output-format stream-json)]

ClaudeCodeTool: deprecated → 후속 CR에서 제거
```

### 2.3 컴포넌트 책임

#### `ClaudeCliAdapter` (서버 in-process, 신규)

- `LLMAdapter` 인터페이스 구현 (`getProvider`, `chat`, `chatStream`, `transformToolDefs`, `parseToolCalls`, `capabilities`)
- 요청 컨텍스트의 `X-Aimbase-Agent-Id` 추출
- `AgentRegistryService`에 agent 조회 (활성 여부, endpoint 정보)
- HTTP 클라이언트로 Runner의 `/v1/chat` / `/v1/chat/stream` 호출
- NDJSON 응답을 `LLMResponse` / `LLMStreamChunk` 로 매핑 (CR-070 `readStreamWithSink` 로직 이식)
- 도구 호출 응답은 `LLMResponse.toolCalls` 채워서 반환 (CR-050 동작 유지)

#### `ClaudeCliRunner` (별도 프로세스, 신규 — aimbase-agent 모듈에 모드 추가)

- HTTP API 노출 (`/v1/chat`, `/v1/chat/stream`, `/v1/cancel`, `/v1/health`)
- 기존 `ClaudeCliWorkerPool`을 내부에서 보유 (BIZ-100 워커 상한 유지)
- 요청 들어오면 worker pool에서 borrow → CLI 실행 → NDJSON 응답
- aimbase-agent 등록 채널(STUN/TURN 위 SSE) 위에 동작

#### `ClaudeCliWorker` / `ClaudeCliCommandBuilder` (이동)

- 위치만 `aimbase-tool-sdk-mcp` (또는 신규 sub-module `aimbase-cli-runner`)로 이동
- API 변경 없음 — 기존 단위 테스트 그대로 통과해야 함
- platform-core에서는 import 제거 (이번 CR 후 사용 금지)

#### `ClaudeCodeTool` (deprecated 마킹)

- `@Deprecated` 어노테이션 + 로그에 "ClaudeCliAdapter로 마이그레이션 필요" 경고
- 기존 호출처(워크플로우 LLM_CALL 등) 식별 → CR-072로 마이그레이션
- v8.7.0에서 제거 예정 (별도 CR)

### 2.4 시퀀스 — 스트리밍 호출

```
브라우저              Aimbase 서버               AgentRegistry        ClaudeCliRunner       claude CLI
   │                       │                          │                    │                  │
   │ POST /chat (SSE)      │                          │                    │                  │
   │ X-Api-Key             │                          │                    │                  │
   │ X-Aimbase-Agent-Id    │                          │                    │                  │
   │──────────────────────▶│                          │                    │                  │
   │                       │ SSE Emitter 시작         │                    │                  │
   │                       │ ThreadLocal STREAM_SINK  │                    │                  │
   │                       │ ─ 헤더 검증              │                    │                  │
   │                       │ ─ ClaudeCliAdapter 선택  │                    │                  │
   │                       │                          │                    │                  │
   │                       │ resolveAgent(agentId)    │                    │                  │
   │                       │─────────────────────────▶│                    │                  │
   │                       │◀─ AgentEndpoint 회신 ────│                    │                  │
   │                       │                          │                    │                  │
   │                       │ POST /v1/chat/stream (NDJSON)                  │                  │
   │                       │────────────────────────────────────────────────▶                  │
   │                       │                          │                    │ spawn claude     │
   │                       │                          │                    │─────────────────▶│
   │                       │                          │                    │                  │
   │                       │                          │                    │ stream-json      │
   │                       │                          │                    │◀ ── NDJSON ── ──│
   │                       │ NDJSON delta 수신        │                    │                  │
   │                       │◀─────────────────────────────────────────────│                  │
   │ SSE delta             │                          │                    │                  │
   │◀──────────────────────│                          │                    │                  │
   │                       │ ... (반복)              │                    │                  │
   │                       │                          │                    │                  │
   │                       │ NDJSON result 수신       │                    │                  │
   │                       │◀─────────────────────────────────────────────│                  │
   │ SSE done              │                          │                    │                  │
   │◀──────────────────────│                          │                    │                  │
```

---

## 3. 인터페이스 명세

### 3.1 `ClaudeCliAdapter` (Java)

```java
package com.platform.llm.adapter;

public class ClaudeCliAdapter implements LLMAdapter {
    public static final String PROVIDER = "anthropic-cli";

    private final ClaudeCliRunnerClient runnerClient;
    private final AgentRegistryService agentRegistry;
    private final String defaultModel;

    public ClaudeCliAdapter(ClaudeCliRunnerClient runnerClient,
                            AgentRegistryService agentRegistry,
                            String defaultModel) { ... }

    @Override public String getProvider() { return PROVIDER; }
    @Override public List<String> getSupportedModels() { return List.of(); }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        String agentId = RequestContext.requireAgentId();      // ThreadLocal 또는 RequestScope
        AgentEndpoint endpoint = agentRegistry.resolveActive(agentId);
        return runnerClient.chat(endpoint, request);
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> consumer) {
        String agentId = RequestContext.requireAgentId();
        AgentEndpoint endpoint = agentRegistry.resolveActive(agentId);
        runnerClient.chatStream(endpoint, request, consumer);
    }

    @Override public Object transformToolDefs(List<UnifiedToolDef> tools) { return null; }
    @Override public List<ToolCall> parseToolCalls(Object nativeResponse) {
        if (nativeResponse instanceof LLMResponse r) return r.toolCalls() != null ? r.toolCalls() : List.of();
        return List.of();
    }
}
```

**`ClaudeCliRunnerClient`** — HTTP 클라이언트 (신규)

```java
public class ClaudeCliRunnerClient {
    LLMResponse chat(AgentEndpoint endpoint, LLMRequest request);
    void chatStream(AgentEndpoint endpoint, LLMRequest request, Consumer<LLMStreamChunk> consumer);
    void cancel(AgentEndpoint endpoint, String runId);
    HealthStatus health(AgentEndpoint endpoint);
}
```

### 3.2 `ClaudeCliRunner` HTTP API

#### POST `/v1/chat`

단발 응답 (non-streaming).

**요청 헤더**:
```
Content-Type: application/json
X-Api-Key: <runner-api-key>
```

**요청 본문**:
```json
{
  "run_id": "session-abc-123",
  "model": "claude-sonnet-4-5",
  "tool_mode": "AIMBASE",          // AIMBASE | NATIVE | HYBRID (CR-069)
  "messages": [
    {"role": "user", "content": "..."}
  ],
  "system_prompt_override": "...",  // optional
  "config_dir": "/path/to/.claude", // optional
  "max_tokens": 16000,              // optional
  "fork_session": false             // optional, 기존 ClaudeCliBranchScope 호환
}
```

**응답 본문**:
```json
{
  "run_id": "session-abc-123",
  "model": "claude-sonnet-4-5",
  "content": "...",
  "tool_calls": [
    {"id": "...", "name": "...", "input": {...}}
  ],
  "usage": {"input_tokens": 0, "output_tokens": 0},
  "finish_reason": "end_turn"
}
```

#### POST `/v1/chat/stream`

NDJSON 스트림 응답.

**요청**: `/v1/chat`과 동일 (`Accept: application/x-ndjson`).

**응답**: `Transfer-Encoding: chunked` + NDJSON. 한 줄당 한 이벤트.

```
{"type":"system","subtype":"init","session_id":"..."}
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"안녕"}]}}
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"하세요"}]}}
{"type":"tool_use","id":"...","name":"Read","input":{...}}
{"type":"tool_result","tool_use_id":"...","content":"..."}
{"type":"result","usage":{...},"finish_reason":"end_turn","total_cost_usd":0.0042}
```

→ Adapter는 이 NDJSON을 `LLMStreamChunk`로 변환:
- `assistant.content[].text` → `LLMStreamChunk.text(delta)`
- `tool_use` → `ToolUseStart` 이벤트
- `tool_result` → `ToolResultEvent`
- `result` → `LLMStreamChunk.done(usage, finishReason, toolCalls)`

#### POST `/v1/cancel`

진행 중인 실행 취소.

**요청 본문**:
```json
{ "run_id": "session-abc-123" }
```

**응답**: `{"cancelled": true}` 또는 `{"cancelled": false, "reason": "not_found"}`

#### GET `/v1/health`

```json
{
  "status": "UP",
  "cli_version": "claude-code/1.x.y",
  "active_workers": 2,
  "max_workers": 5,
  "uptime_seconds": 12345
}
```

### 3.3 헤더 규약

| 헤더 | 출처 | 용도 |
|---|---|---|
| `X-Api-Key` | 소비자앱/Claude Code/MCP 클라이언트 | Aimbase 서버 인증 (기존) |
| `X-Aimbase-Agent-Id` | 호출자가 명시 | ClaudeCliAdapter 라우팅 키 |
| `X-Runner-Api-Key` | Aimbase 서버 → Runner 내부용 | Runner 측 인증 (서버↔Runner 채널) |

### 3.4 `connection` 엔티티 변경 사항

**`config` JSONB 필드에 추가**:
```json
{
  "model": "claude-sonnet-4-5",
  "tool_mode": "AIMBASE",
  "config_dir": "/path/to/.claude"
}
```

**`runner_url` 박지 않음** — Runner 위치는 agentId → AgentRegistry 조회로 결정.

### 3.5 `RequestContext`

기존 ThreadLocal 패턴 확장:

```java
public final class RequestContext {
    private static final ThreadLocal<String> AGENT_ID = new ThreadLocal<>();

    public static void setAgentId(String id) { AGENT_ID.set(id); }
    public static String getAgentId() { return AGENT_ID.get(); }
    public static String requireAgentId() {
        String id = AGENT_ID.get();
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "X-Aimbase-Agent-Id header required for ClaudeCliAdapter");
        }
        return id;
    }
    public static void clear() { AGENT_ID.remove(); }
}
```

**Servlet Filter** (신규 `AgentIdRequestFilter`):
- 모든 요청에서 `X-Aimbase-Agent-Id` 헤더 추출 → `RequestContext.setAgentId`
- `finally`에서 `clear`
- VT 전파: `ChatController.streamResponse()` 처럼 헤더 값을 capture해서 VT 시작 시 다시 set

---

## 4. 스트리밍 설계 — 4단 파이프

### 4.1 데이터 흐름

```
[브라우저]
   ↑ SSE 5종 (delta/thinking/tool_use_start/tool_result/done) — ChatController 기존
[Aimbase 서버]
   ↑ ThreadLocal STREAM_SINK Consumer<StreamEvent> — SubagentRunner 패턴 기존
   ↑ ClaudeCliAdapter.chatStream
   ↑   - HTTP NDJSON 응답을 라인 단위로 읽기
   ↑   - 각 라인 → LLMStreamChunk → StreamEvent 매핑 → STREAM_SINK.accept()
[ClaudeCliRunner]
   ↑ Worker.drainStdout — NDJSON 라인 처리 (CR-050 기존)
   ↑ HTTP chunked 응답 작성
[claude CLI]
   ↑ stream-json (NDJSON)
```

### 4.2 NDJSON → StreamEvent 매핑 (CR-070 readStreamWithSink 이식)

| NDJSON `type` | StreamEvent | 비고 |
|---|---|---|
| `system.init` | (무시) | Runner 측 로깅만 |
| `assistant.message.content[].text` (델타) | `TextDelta` | 누적 → 텍스트 응답 |
| `assistant.message.content[].thinking` | `ThinkingDelta` | Extended Thinking |
| `tool_use` | `ToolUseStart` | id/name/input 추출 |
| `tool_result` | `ToolResultEvent` | tool_use_id/output/isError |
| `result` | `Done` | usage/finish_reason/total_cost_usd |

`Adapter`가 이 매핑을 수행하고 `STREAM_SINK.accept(event)` 호출 → `ChatController.sseSink`가 SSE로 송출.

### 4.3 도구 호출 처리

**중요**: ClaudeCliAdapter는 도구 정의를 `transformToolDefs`로 보내지 않음 (`null` 반환). 도구는 **MCP 채널**로 CLI에 직접 노출됨 — `aimbase-agent --mcp-stdio`가 노출하는 MCP 서버를 CLI가 `--mcp-config`로 연결.

→ Runner는 `--strict-mcp-config`(AIMBASE 모드) 등을 CommandBuilder로 적용하고, MCP 서버 자체는 aimbase-agent의 기존 SDK 도구 노출 흐름(CR-042/044) 그대로 사용.

→ `tool_use` 이벤트가 NDJSON으로 흘러들어오면 Adapter가 `LLMResponse.toolCalls`에 누적해서 호출자에게 반환 (CR-050 동작 유지).

---

## 5. 마이그레이션 경로

### 5.1 ClaudeCliLlmAdapter 즉시 삭제

운영 중이 아니므로 단계적 deprecation 없이 본 CR에서 직접 삭제한다.

**삭제 대상**:
- `backend/platform-core/src/main/java/com/platform/llm/adapter/ClaudeCliLlmAdapter.java`
- `backend/platform-core/src/test/java/com/platform/llm/adapter/ClaudeCliLlmAdapterTest.java`
- `backend/platform-core/src/test/java/com/platform/llm/claudecli/ClaudeCliLlmAdapterIT.java`

**`ConnectionAdapterFactory` 변경**:
```java
if (provider.equals("anthropic-cli")) {
    return new ClaudeCliAdapter(...);  // 단일 경로, 분기 없음
}
```

**Connection 데이터**: 기존 `provider=anthropic-cli` 레코드는 신규 ClaudeCliAdapter용 스키마(`config.tool_mode`, `config.config_dir`)로 재작성. Runner URL은 박지 않음 (agentId로만 라우팅).

### 5.2 ClaudeCodeTool 즉시 삭제

운영 중이 아니므로 본 CR에서 직접 삭제.

**삭제 대상 (소스)**:
- `backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeTool.java`
- `backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeToolConfig.java`
- `backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeNotificationService.java`
- `backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeCircuitBreaker.java`
- `backend/platform-core/src/main/java/com/platform/tool/builtin/AgentAccountPoolManager.java`
- `backend/platform-core/src/main/java/com/platform/runtime/ClaudeCodeRuntimeAdapter.java`

**삭제 대상 (테스트)**:
- `backend/platform-core/src/test/java/com/platform/tool/ClaudeCodeToolTest.java`
- `backend/platform-core/src/test/java/com/platform/tool/builtin/ClaudeCodeToolRetryTest.java`
- `backend/platform-core/src/test/java/com/platform/tool/builtin/ClaudeCodeToolStreamingTest.java`

**유지 (Runner로 이동)**: `AimbaseMcpConfigGenerator` — Runner 내부에서 MCP config 생성에 재사용.

**연계 정리**:
- `ClaudeCodePlatformController` — ClaudeCodeTool 의존 제거, ClaudeCliAdapter 호출로 전환 (또는 컨트롤러 자체 삭제 검토)
- `ChatController.streamResponse()` line 219, 226 — `ClaudeCodeTool.setStreamSink/clearStreamSink` 호출 제거 (CR-070 작업분이지만 Tool 사라지므로 제거)
- 워크플로우 LLM_CALL의 ClaudeCodeTool 호출 케이스 — ClaudeCliAdapter connection 호출로 변경

### 5.3 ClaudeCliWorker/CommandBuilder 모듈 이동

- 현재 위치: `backend/platform-core/src/main/java/com/platform/llm/claudecli/`
- 이동 위치: `backend/cli-runner/src/main/java/com/platform/runner/claudecli/` (신규 모듈)
  또는 `aimbase-tool-sdk-mcp` 확장
- platform-core는 `ClaudeCliRunnerClient`만 의존
- 단위 테스트(`ClaudeCliWorkerTest`, `ClaudeCliWorkerPoolTest`, `ClaudeCliCommandBuilderTest`) 함께 이동
- 통합 테스트(`AdapterComparisonIT`)는 Runner 기동 + Adapter HTTP 호출 형태로 재작성

---

## 6. aimbase-agent `--runner-mode`

### 6.1 모드 추가

기존 `aimbase-agent`는 `--mcp-stdio` (Aimbase SDK 도구 MCP 서버) 모드만 있음. 신규 모드:

```
aimbase-agent --runner-mode \
  --listen 127.0.0.1:8290 \
  --runner-api-key $RUNNER_API_KEY \
  --max-workers 5 \
  --claude-binary /usr/local/bin/claude \
  --aimbase-mcp-stdio   # AIMBASE/HYBRID tool-mode일 때 자동 노출
```

### 6.2 내부 구성

- HTTP 서버 (Spring Boot 또는 Helidon — aimbase-agent 기존 스택 따름)
- 컨트롤러: `RunnerController` (`/v1/chat`, `/v1/chat/stream`, `/v1/cancel`, `/v1/health`)
- `ClaudeCliWorkerPool` (이동된 모듈에서 재사용)
- `ClaudeCliCommandBuilder` (이동된 모듈에서 재사용)

### 6.3 등록/디스커버리

aimbase-agent가 시작 시 Aimbase 서버에 등록 (CR-041 `AgentRegistryService` 기존 흐름):
- 등록 페이로드에 `runner_capability: true`, `runner_endpoint: "http://...:8290"` 추가
- `AgentRegistryEntity`에 컬럼 추가: `runner_endpoint`, `runner_api_key_hash`, `runner_capability`

`AgentRegistryService.resolveActive(agentId)`:
- agent 활성 여부 확인 (heartbeat 5분 이내 — BIZ-079)
- `runner_capability=true`인 경우만 ClaudeCliAdapter가 사용 가능
- 그 외엔 `AgentNotAvailable` 예외 → 400 응답

---

## 7. BIZ 룰 재정의

| 룰 | 기존 | 신규 |
|---|---|---|
| **BIZ-099** | Claude CLI 어댑터는 테넌트 피처 플래그 허용 시에만 활성화 | ClaudeCliAdapter는 `X-Aimbase-Agent-Id` 헤더 필수. agent의 `runner_capability=true` 검증. 테넌트 피처 플래그(`llm.anthropic-cli.enabled-tenants`)는 유지 — 라우팅 정책 게이트로 의미 변경 |
| **BIZ-100** | run당 CLI 워커 기본 상한 5개, 초과 시 큐잉 | (변경 없음) Runner 내부에서 ClaudeCliWorkerPool이 동일 정책 적용 |
| **BIZ-101 (신규)** | — | ClaudeCliAdapter 호출 시 `X-Aimbase-Agent-Id` 헤더 누락은 400 에러. 라우팅 자동화는 별도 CR. |

---

## 8. 테스트 전략

### 8.1 단위 테스트

| 대상 | 테스트 |
|---|---|
| `ClaudeCliAdapter.chat` | agent resolve 모킹 + Runner client 모킹, 응답 파싱 검증 |
| `ClaudeCliAdapter.chatStream` | NDJSON → StreamEvent 매핑 검증 (CR-070 패턴 차용) |
| `RequestContext.requireAgentId` | 누락 시 ResponseStatusException(400) |
| `ClaudeCliRunnerClient` | NDJSON 라인 파싱, 취소, 에러 처리 |
| `ClaudeCliRunner Controller` | `/v1/chat` 입력 검증, `/v1/cancel` 동작 |
| `ClaudeCliWorker/Pool/CommandBuilder` | 기존 테스트 그대로 통과 (이동 후) |
| `AgentRegistryService.resolveActive` | runner_capability=false → 예외 |

### 8.2 통합 테스트

| 시나리오 | 환경 |
|---|---|
| 서버 Runner (localhost:8290) + Adapter | docker-compose: aimbase-server + aimbase-agent --runner-mode |
| 사용자 PC Runner 시뮬레이션 | aimbase-agent를 별도 컨테이너로 띄우고 STUN/TURN 채널로 라우팅 |
| 어댑터 동등성 (CR-067/068) | `AdapterComparisonIT`을 Adapter+Runner 형태로 재작성, ToolMode=AIMBASE에서 결과 비교 |
| 스트리밍 4단 파이프 | ChatController SSE → Adapter → Runner → CLI, delta/done 이벤트 카운트 |

### 8.3 회귀 테스트

- CR-067: ToolResultRenderer 본문 노출 — Runner의 NDJSON `tool_result` 매핑이 본문 보존하는지
- CR-068: API 어댑터와의 결과 동등성 — 동일 프롬프트/도구로 산출물 비교
- CR-070: ClaudeCodeTool 스트리밍 — deprecated 후에도 v8.6.x 동안 동작 유지

---

## 9. 롤아웃 단계 (Phase 1~6)

| Phase | 내용 | 산출물 | PR 단위 |
|---|---|---|---|
| **1. 모듈 분리 + 기존 삭제** | `ClaudeCliWorker/Pool/CommandBuilder` 신규 모듈로 이동 + `ClaudeCliLlmAdapter` 즉시 삭제 + `ClaudeCodeTool` 및 동반 클래스/테스트 삭제 + `ChatController`/`ConnectionAdapterFactory`의 의존 정리 | 신규 모듈 + 단일 경로 | 1 PR |
| **2. Runner HTTP** | aimbase-agent `--runner-mode` + `RunnerController` 4 endpoint (`/v1/chat`, `/v1/chat/stream`, `/v1/cancel`, `/v1/health`) | aimbase-agent 신버전 | 1 PR |
| **3. AgentRegistry 확장** | `runner_endpoint`/`runner_api_key_hash`/`runner_capability` 컬럼 + V57 Flyway | 마이그레이션 + 서비스 | 1 PR |
| **4. ClaudeCliAdapter** | `ClaudeCliAdapter` + `ClaudeCliRunnerClient` + `RequestContext` + `AgentIdRequestFilter` 신설 + `ConnectionAdapterFactory` 단일 경로 등록 | 신규 어댑터 동작 | 1 PR |
| **5. 통합 테스트 + 가이드** | docker-compose 시나리오 + `AdapterComparisonIT` 재작성 + 가이드 문서 4종 갱신 (api/ops/sdk/claudecode-mcp-setup) | 통합 테스트 PASS + 문서 | 1 PR |

총 5 PR. Phase 1에서 기존 자산 정리·이동을 한 번에 처리해 마이그레이션 분기 코드 자체가 발생하지 않는다.

---

## 10. 가이드 문서 갱신

| 문서 | 갱신 항목 |
|---|---|
| `aimbase-api-guide.md` | ClaudeCliAdapter 사용 시 `X-Aimbase-Agent-Id` 헤더 필수 명시. 외부 도구(Claude Code MCP) 설정 예시 |
| `aimbase-ops-guide.md` | Runner 운영 (aimbase-agent `--runner-mode` 기동, Runner API Key 발급, AgentRegistry 조회 절차) |
| `aimbase-sdk-guide.md` | 자체 소비자앱이 헤더 명시 책임 (자동 주입은 후속 CR) |
| (신규) `claudecode-mcp-setup.md` | Claude Code 사용자가 Aimbase MCP 연결 시 `X-Aimbase-Agent-Id` 박는 방법 |

---

## 11. 보안 고려사항

| 항목 | 1단계 | 후속 |
|---|---|---|
| 서버↔Runner 인증 | API Key 헤더 (`X-Runner-Api-Key`) | mTLS 또는 JWT 단기 토큰 |
| 호출자→서버 인증 | `X-Api-Key` 기존 패턴 | 변경 없음 |
| agent-id 위변조 | AgentRegistry 활성 여부 검증 (heartbeat) | agent-id ↔ tenant 일치성 검증 (별도 CR) |
| Runner 응답 검증 | NDJSON 파싱 실패 → 에러 이벤트 | 응답 서명 (mTLS와 묶어서) |
| 토큰 비용 | Runner는 사용자 PC → 사용자 정액 플랜 사용 (서버 비용 0) | 변경 없음 |

---

## 12. 위험과 완화

| 위험 | 영향 | 완화 |
|---|---|---|
| Runner 미응답 (사용자 PC 꺼짐) | ClaudeCliAdapter 호출 실패 | AgentRegistry heartbeat → 친절한 400 에러 ("agent inactive") |
| NDJSON 라인 파싱 실패 | 스트림 끊김 | 라인 단위 try/catch, 파싱 실패는 로그 후 다음 라인 진행 (CR-070 패턴) |
| Worker pool 고갈 | 큐잉 대기 (BIZ-100) | 기존 Pool 정책 유지, Runner /v1/health에 active_workers 노출 |
| ClaudeCodeTool 호환성 | 기존 워크플로우 깨짐 | v8.6.x 동안 deprecated만, v8.7.0에서 별도 CR로 제거 |
| 테넌트별 agent 매칭 오류 | 다른 테넌트 agent 사용 가능성 | AgentRegistry에서 tenant_id 검증 추가 (Phase 3) |

---

## 13. 범위 외 (별도 CR)

- 라우팅 자동화 (로컬 토큰 자동 주입, IP 보조 매칭, 로컬 디스커버리)
- 자체 소비자앱 SDK 자동 헤더 주입
- mTLS/JWT 보안 강화
- OpenAI Codex / Google Gemini CLI 어댑터
- ~~ClaudeCodeTool 클래스 실제 삭제~~ → 본 CR에서 즉시 삭제로 변경
- ~~ClaudeCliLlmAdapter 클래스 실제 삭제~~ → 본 CR에서 즉시 삭제로 변경

---

## 14. 완료 기준 (Definition of Done)

- [ ] Phase 1~6 PR 머지
- [ ] V57 Flyway 마이그레이션 적용 (AgentRegistry 컬럼 추가)
- [ ] 단위 테스트 PASS (Adapter + Runner + RequestContext)
- [ ] 통합 테스트 PASS (서버 Runner + 사용자 PC Runner 시뮬레이션)
- [ ] 회귀 테스트 PASS (CR-067/068 어댑터 동등성, CR-070 스트리밍)
- [ ] aimbase-agent 신규 빌드 산출물 (CR-042 빌드 파이프라인 활용)
- [ ] 가이드 문서 4개 갱신
- [ ] BIZ-099/100/101 룰 재정의 반영
- [ ] CR_변경_이력.md 상태를 ✅ 구현 완료로 갱신
