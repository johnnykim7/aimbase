# 원본 요구사항 — CR-071 ClaudeCliAdapter (3경로 통일)

- **일자**: 2026-04-27
- **요청자**: sykim (대화)
- **CR**: CR-071
- **관련 CR**: CR-042 (aimbase-agent 독립 모듈), CR-044 (CLI 두뇌 + Aimbase 손발), CR-050 (Claude CLI → LLM 어댑터), CR-069 (CLI 호출 공통 빌더 + ToolMode), CR-070 (ClaudeCodeTool 스트리밍 중계)

---

## 발단

CR-070 진행 중 "Claude에게 작업 시키기"가 3개 경로로 분산되어 있다는 구조적 문제를 사용자가 지적.
- 인터페이스 3개 → 거버넌스(정책/Hook/max_iter/감사)가 경로마다 부분 적용
- ToS 경계는 "어디서 CLI를 띄우는가"의 문제이지 "무엇을 호출하는가"가 아님
- ClaudeCodeTool은 "ToS로 어댑터 못 쓰는 자리에 만든 우회로"라는 본질 — 도구가 아니라 LLM 어댑터로 재배치되어야 함

---

## 현재 3경로 (분산)

1. **API**: `OrchestratorEngine` → `LLMAdapter.chat()` → `AnthropicAdapter` → Anthropic REST API
   - 토큰 과금, 거버넌스 풀세트 적용
2. **CLI 어댑터** (CR-050 `ClaudeCliLlmAdapter`): `OrchestratorEngine` → `ClaudeCliLlmAdapter` → 서버 in-process `ClaudeCliWorker` → CLI
   - BIZ-099 피처 플래그로 외부 테넌트 차단 (정액 플랜 ToS 우려)
   - CLI가 도구 루프 자율 통제 → Stop Hook/max_iter 미적용 (CR-067/068 노트)
3. **ClaudeCodeTool** (CR-044 + CR-070): 워크플로우 LLM_CALL → `ToolExecutor` → `ClaudeCodeTool` → CLI 자식 프로세스 (서버 또는 사용자 PC aimbase-agent)
   - "도구로 LLM 위임" 안티패턴
   - tool_bridge=`aimbase-mcp-only`로 (2)의 취지 흡수 가능 (CR-044)

---

## 통일안 — ClaudeCliAdapter

### 핵심 아이디어

**모든 Claude 호출을 LLMAdapter 단일 인터페이스로 모은다. CLI를 띄우는 위치(=Runner)를 connection 설정으로 자유 배치.**

```
OrchestratorEngine
  ├─ AnthropicAdapter        (Anthropic REST API — 기존)
  ├─ OpenAIAdapter
  └─ ClaudeCliAdapter        ← 이번 CR (HTTP로 Runner 호출, Runner가 Claude CLI 실행)
       └─ ClaudeCliRunner HTTP API
            ├─ POST /v1/chat
            ├─ POST /v1/chat/stream  (NDJSON / SSE)
            └─ POST /v1/cancel
```

### 명명 체계

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| `ClaudeCliAdapter` | Aimbase 서버 (in-process) | LLMAdapter 구현체. Runner를 HTTP로 호출 |
| `ClaudeCliRunner` | 별도 프로세스 (서버 또는 사용자 PC) | HTTP 서비스. 안에서 Worker로 CLI 실행 |
| `ClaudeCliWorker` (CR-050) | Runner 내부 | CLI 자식 프로세스 1개를 다루는 워커 |
| `ClaudeCliCommandBuilder` (CR-069) | Runner 내부 | CLI 명령 빌더 (ToolMode 스위치 포함) |

### 네이밍 정책 (제품명 기준)

LLM CLI 시장은 Anthropic Claude Code CLI 뿐만 아니라 OpenAI Codex CLI, Google Gemini CLI 등 다양화 추세. 그러나 각 CLI는 옵션 체계·프롬프트 주입·스트림 포맷·MCP 지원 여부가 모두 다름. **공통 추상화는 추측성(YAGNI)** 이므로 이번 CR은 Claude CLI 전용 어댑터로 좁혀 정직하게 명명한다.

| 분류 | 어댑터 |
|---|---|
| Anthropic REST API | `AnthropicAdapter` (기존) |
| Claude Code CLI (HTTP 사이드 프로세스) | `ClaudeCliAdapter` (이번 CR) |
| OpenAI REST API | `OpenAIAdapter` (기존) |
| 향후: OpenAI Codex CLI | `CodexCliAdapter` |
| 향후: Google Gemini CLI | `GeminiCliAdapter` |

→ **API 어댑터(`*Adapter`) vs CLI 어댑터(`*CliAdapter`)** 대칭으로 종류 즉시 식별. 미래에 공통 부분이 명확해지면 그때 추출. 지금은 추출하지 않는다.

### Runner 배치 자유

| 시나리오 | Runner 위치 | connection.runner-url |
|---|---|---|
| 내부 개발/벤치마크 | 같은 서버 (59번) | `http://localhost:8290` |
| 운영 (사용자 본인 PC) | 사용자 PC | `http://user-host:8190` (혹은 STUN/TURN 경유) |
| 테넌트별 분기 | connection 단위로 | `${RUNNER_URL}` |

→ ToS 경계는 **Runner 위치**로 자연 해결. BIZ-099 피처 플래그가 `connection.runner-url` 매핑 정책으로 단순화.

### CLI 모드 라우팅 (CR-069 ToolMode 재활용)

connection 설정에 `tool-mode` 필드 추가:

| 모드 | CLI 옵션 | 의미 |
|---|---|---|
| `AIMBASE` | `--strict-mcp-config --tools ""` + Aimbase MCP만 주입 | Aimbase SDK 도구만 노출 (거버넌스 100% 적용) |
| `NATIVE` | `--bypass-permissions` + 기본 도구 | CLI 자체 도구 자율 사용 (빠름, 무통제) |
| `HYBRID` | strict + Aimbase MCP + 화이트리스트 | 둘 다 노출 |

워크플로우 LLM_CALL에서 connection을 골라 모드 선택.

---

## 얻는 것

1. **ClaudeCodeTool 폐기 가능** → "도구로 LLM 위임" 안티패턴 제거. 워크플로우 LLM_CALL 한 곳에서 모든 LLM 호출 통일.
2. **거버넌스 일원화** — Stop Hook, max_iter, 정책, 감사 로깅이 어댑터 레이어 한 군데에서 적용. CR-067/068에서 발견된 "CLI 자율 통제로 외부 정책 미적용" 트레이드오프 해소 가능 (단, ToolMode=NATIVE는 의도적 예외).
3. **ToS 자연 해결** — Runner 위치가 곧 책임 주체. 정액 플랜은 사용자 PC Runner → 사용자 본인 사용. 서버 Runner → 내부 전용. BIZ-099 피처 플래그가 connection.runner-url 정책으로 단순화.
4. **호출자 코드 단순화** — `LLMAdapter.chat()` 한 인터페이스만 알면 됨. ClaudeCodeTool 분기 제거.
5. **기존 자산 재활용** — aimbase-agent (CR-042)가 이미 사용자 PC에 설치되는 모듈, MCP 서버 + STUN/TURN 인프라 보유 → Runner 역할 흡수에 적합.

---

## 기존 자산 재배치

- **aimbase-agent (CR-042)**: Runner 역할 흡수, `--runner-mode` 추가. 현재는 Aimbase SDK 도구의 MCP 서버만 노출 → CLI 실행 + HTTP 엔드포인트 추가.
- **ClaudeCliLlmAdapter (CR-050)**: `ClaudeCliAdapter`로 진화 (이름 단축 — `Llm` 접미사 중복 제거, LLMAdapter 인터페이스 구현체이므로 자명). 현재는 서버 in-process `ClaudeCliWorker` 직접 실행 → Runner HTTP 클라이언트로 변환. `ClaudeCliWorker/Pool`은 Runner 내부 구현으로 이동.
- **ClaudeCliCommandBuilder (CR-069)**: Runner 내부 명령 빌더로 재배치. ToolMode 스위치 그대로 사용.
- **ClaudeCodeTool (CR-044)**: deprecated 마킹 → 후속 CR에서 제거. CR-070 Phase A/B 스트리밍 작업 결과는 Runner NDJSON 패스스루로 이전.

---

## 다음 세션 진행 시작점 (이 세션의 작업 범위)

1. ✅ **CR-071 발번** + `docs/origins/` 원본 작성 (이 문서)
2. 보류 3건 사용자 확인 (보안/멀티사용자 라우팅/스트리밍)
3. **ClaudeCliAdapter 인터페이스 + ClaudeCliRunner HTTP API 명세 설계** (T3 설계서)
4. **aimbase-agent에 `--runner-mode` 추가 골격** (LLM 호출 endpoint 노출)
5. **ClaudeCliLlmAdapter → ClaudeCliAdapter 마이그레이션 경로** 설계
6. **ClaudeCodeTool deprecation 계획** 수립

실제 구현은 설계서 승인 후 별도 Phase로 진행.

---

## 결정 사항 (2026-04-27 사용자 확정)

### 1. Runner 보안

**1-1. 인증**: **API Key 헤더 (`X-Api-Key`)** — 기존 FlowGuard/Aimbase 인증 패턴 그대로. 1단계는 단순 채택. 키 관리는 테넌트별 1키, connection 등록 시 발급.
- mTLS / JWT는 후속 강화로 보존 (헤더 검증 위치는 동일)

**1-2. NAT 통과**: **기존 STUN/TURN + AgentRegistry 인프라 재활용** (CR-041/CR-042).
- aimbase-agent가 이미 서버에 등록되며, RemoteAgentToolExecutor가 직접 연결 또는 TURN 릴레이로 통신 처리 중
- ClaudeCliAdapter는 동일 채널 위에 LLM 호출 endpoint만 추가
- STUN/TURN/등록 로직은 신규 작성 없음

### 2. 멀티 사용자 라우팅

**`X-Aimbase-Agent-Id` 요청 헤더 필수**. 호출자가 자기 PC agent-id를 명시.

```
요청 헤더 X-Aimbase-Agent-Id 있으면 → 그 agent로 라우팅
없으면 → 400 에러 ("X-Aimbase-Agent-Id required for ClaudeCliAdapter")
```

- **이유**: 1단계는 가장 확실한 방법으로 단순화. 자동화는 후속 CR.
- **호출자 책임**:
  - 자체 소비자앱(bp-openmall 등) → 설정 파일에 agent-id 박고 헤더 자동 주입
  - Claude Code 등 외부 MCP 클라이언트 → MCP 설정의 headers에 agent-id 직접 입력
  - 기타 도구 → 도구별 헤더 설정에 입력
- **자동화 미포함**: 로컬 토큰 자동 주입, IP 보조 매칭, 로컬 디스커버리 등은 별도 CR (CR-072 또는 후속)
- **connection은 본래 의미 유지**: 모델/제공자/모드 정보만, Runner URL 박지 않음

### 3. 스트리밍

**기존 SSE + STREAM_SINK + NDJSON 자산 그대로 재활용**. Runner HTTP는 NDJSON 라인 스트림.

코드 자산:
- `ChatController` SSE 5종 이벤트 (delta/thinking/tool_use_start/tool_result/done)
- `SubagentRunner.STREAM_SINK` ThreadLocal 패턴
- `ClaudeCliWorker.drainStdout()` NDJSON 라인 처리
- `ClaudeCodeTool.readStreamWithSink()` stream-json → StreamEvent 매핑
- `StreamEvent.SubagentStart/Done` 이벤트 (Runner 상태로 그대로 적용)

4단 파이프:
```
[브라우저] ←─SSE(기존)─ [Aimbase 서버: ClaudeCliAdapter]
                            ↑ ThreadLocal STREAM_SINK (기존)
                            ↑ NDJSON 역방향 채널
                       [Runner: aimbase-agent]
                            ↑ stream-json (기존 매핑)
                       [claude CLI]
```

신규 작성 ~30%, 기존 자산 이식 ~70%.

---

## 비고

- 이 컨셉은 2026-04-27 대화 종료 시점에 사용자가 "다음 세션에서 진행" 지시. 메모리 인덱스: `project_cr071_claudecli_adapter.md`.
- CR-070은 "현 ClaudeCodeTool 경로의 단기 UX 개선"이고, CR-071은 "구조 자체 통일"이라 양립 가능. CR-070 결과물은 CR-071의 Runner NDJSON 패스스루로 자연 흡수.
- BIZ-099 (정액 플랜 ToS 격리) / BIZ-100 (CLI 워커 상한) 두 비즈니스 룰의 의미가 CR-071 적용 후 재정의 필요 (Runner 위치 기준으로 재서술).
- **네이밍 정책**: 이번 CR은 Claude Code CLI 전용. OpenAI Codex / Google Gemini 등 향후 CLI는 별도 어댑터(`CodexCliAdapter`, `GeminiCliAdapter`)로 추가하며, 공통 추상화는 추출 시점이 명확해진 후에만 진행 (YAGNI).
- **명명 변천 기록**: 초안 `SidecarLlmAdapter` → 사용자 지적으로 `ClaudeSidecarAdapter`(Claude 전용 명시) → 사이드카 패턴 의미 검토로 `ClaudeCliRunnerAdapter` → API/CLI 대칭 정리로 최종 `ClaudeCliAdapter`(별도 프로세스는 `ClaudeCliRunner`).
