# 원본 요구사항 — Claude CLI를 LLM 어댑터로 승격

**원본 대화 기간**: 2026-04-14 ~ 2026-04-16 (세션 3개)
**원본 세션 파일**:
- `~/.claude/projects/-Users-sykim-Documents-GitHub-bp-platform-aimbase/becc9f26-3a83-4870-9029-cc224015aaea.jsonl` (2026-04-14, 최초)
- `~/.claude/projects/-Users-sykim-Documents-GitHub-bp-platform-aimbase/b8d67f46-9736-4bf7-907d-ae164cf5a340.jsonl` (2026-04-16)
- `~/.claude/projects/-Users-sykim-Documents-GitHub-bp-platform-aimbase/91ea6e24-b49a-430c-b73e-df4bee431021.jsonl` (2026-04-16)

**복원 일자**: 2026-04-16 (세션 3개를 병합하여 설계 재구성)

---

## 1. 동기 (Motivation)

### 1-1. 사용자 원문 문제 제기
> "현재 ClaudeCodeTool(TOOL_CALL 경로)은 매 호출마다 Node.js 기동(500ms+) 발생. N개 LLM_CALL Step을 가진 워크플로우는 N × 500ms 오버헤드."

### 1-2. 비용 논거 — 구독제 vs API
- **현재 AnthropicAdapter**: API Key 토큰당 과금
- **제안 ClaudeCliLlmAdapter**: OAuth 기반 **Claude Pro/Max 정액제** ($100~200/월)로 LLM_CALL 비용 대체
- 사용자 판단: "구독자 관점에서 정액제를 유지하면서 오버헤드를 줄이려면 Claude CLI 자체를 상주시켜야"

### 1-3. 성능 논거
| 시나리오 | 현재 (ClaudeCodeTool) | 제안 (CliLlmAdapter) |
|---|---|---|
| 10개 LLM_CALL 워크플로우 | 10 × 500ms = **5초** | 1 × 500ms = **0.5초** |
| 기동 오버헤드 | 매번 발생 | 1회만 발생 |
| **오버헤드 감소** | — | **10배** |

- T4 병렬 브랜치 테스트: $0.07 → $0.007 (캐시 재사용으로 약 10배 비용 절감 검증)
- 사용자 강조: **"이게 이 설계의 핵심 이득"**

---

## 2. 핵심 설계 결정

### 2-1. Connection 타입 분리 방식
**결정**: 기존 anthropic 어댑터 확장이 아니라 **별도 `anthropic-cli` 타입 신설**

**검토한 대안**:
- A안: 기존 anthropic 어댑터에 `auth_type` 분기 (API Key / OAuth CLI)
- **B안 (채택)**: 새로운 `anthropic-cli` 어댑터 타입 분리
- 채택 사유: "역할이 명확하게 갈라짐"

### 2-2. 어댑터 구현 방식 차이
| AnthropicAdapter | ClaudeCliLlmAdapter |
|---|---|
| Java 내 HTTP 호출 | 외부 Node.js 바이너리 spawn |
| 0ms 기동 오버헤드 | 500ms 기동 오버헤드 |
| 무상태 | 워커 상주 (풀 관리) |
| API Key | OAuth 토큰 (CLAUDE_CONFIG_DIR) |
| 토큰당 과금 | 구독 정액제 |

### 2-3. WorkerPool 설계
- **키**: `workflowRunId` (또는 request.sessionId())
- **수명**: workflow run 시작 시 lazy spawn → run 종료 시 shutdown
- **기본 구성**: run당 메인 워커 1개 + 병렬 브랜치용 추가 워커
- **동시성**: 같은 워커는 내부 뮤텍스로 직렬화 ("한 번에 한 턴")
- **크래시 복구**: 프로세스 비정상 종료 감지 + 자동 재기동 + 현재 run 실패 처리

### 2-4. CLI 기동 커맨드 (검증 완료)
```bash
claude -p \
  --verbose \
  --input-format stream-json \
  --output-format stream-json \
  --tools "" \
  [--model <model>]
```

**핵심 플래그**:
- `--input-format stream-json`: stdin으로 NDJSON 주입
- `--output-format stream-json`: stdout으로 이벤트 스트림
- `--tools ""`: 도구 전면 차단 → 순수 LLM_CALL만
- ProcessBuilder는 기존 AgentAccountPoolManager.executeLocal 재사용

### 2-5. 세션 소유권 — "CLI가 주인"
- **CLI가 대화 이력 관리**: stream-json 프로세스 내부에 누적, `.jsonl` 파일로 자동 저장
- **Aimbase Redis**: 감사/정책용 병렬 로그만 기록 (기존 SessionManager 유지)
- **첫 턴**: `LLMRequest.messages` 전체를 NDJSON으로 stdin 주입
- **이후 턴**: 마지막 user 메시지만 주입 (CLI가 이전 맥락 기억)
- **중요 제약**: 턴 간 상태 리셋 불가 (T3 테스트로 확인) → **워크플로우당 새 워커가 필수**

### 2-6. 병렬 브랜치 — fork-session
```
Step 1: 메인 워커 W1 (claude -p)
PARALLEL {
  브랜치 A: W1 재사용 (메인 세션 이어감, 기동 0ms)
  브랜치 B: 새 프로세스 W2 = claude --resume <W1_session> --fork-session
  브랜치 C: 새 프로세스 W3 = claude --resume <W1_session> --fork-session
}
Step 3: W1 계속 사용
        (W1은 2a 결과까지만 포함, 2b/2c는 변수 치환으로 주입)
```

**메커니즘**:
- W1이 Step 1 완료 후 세션 파일에 커밋 → W2/W3가 그 상태 읽음
- `--fork-session`으로 원본 세션 보존하며 독립 분기
- 병렬 브랜치는 **캐시 재사용으로 기동 비용 대폭 감소** (약 10배)
- 병렬 브랜치는 **종료 시 파기**

### 2-7. 도구 지원 — 비활성
- `--tools ""` 로 도구 전면 차단
- `transformToolDefs`: 빈 구현 또는 예외
- **이유**: 순수 LLM_CALL 경로만 지원. 도구 호출은 ClaudeCodeTool(Tool 경로)로 계속 처리
- ClaudeCodeTool은 **그대로 유지** (에이전트 자율 작업용)

### 2-8. 스트리밍
- stdin/stdout **stream-json 파이프**로 여러 턴 순차 주입
- stdout에서 `type=result` 이벤트 수신까지 대기 (라인 단위 파싱)
- 응답 이벤트 종류: `type=assistant`, `type=result`, `rate_limit_event`, `system`, `hook_*` 등
- 불필요 이벤트 필터링 필요

### 2-9. 비용 추적
- 현재 설계: "Aimbase Redis 레벨 감사/정책 로그만 기록"
- **미해결**: stream-json 파이프에서 usage(토큰) 정보 추출 방식 — 구현 단계에서 결정
- CLI가 usage 정보를 output 이벤트에 포함하는지 확인 필요

### 2-10. 실패 처리
- **워커 크래시**: 프로세스 비정상 종료 감지 + 자동 재기동 + 현재 run 실패 처리
- **Run 종료 누락 방지**: 워크플로우 예외로 죽어도 워커 정리되도록 try/finally 또는 이벤트 리스너
- **stderr drain 필수**: 별도 스레드로 안 읽으면 파이프 버퍼 차서 프로세스 멈춤

---

## 3. 기존 CR과의 관계

| CR | 관계 | 충돌 여부 |
|---|---|---|
| CR-043 (다중계정 재시도) | **완전 독립** — Tool 경로 개선, Adapter와 무관 | 없음 |
| CR-044 (CLI 두뇌 + MCP 강제) | **완전 독립** — CLI를 Tool로 유지하면서 내부 도구 봉인. Adapter 경로는 별개 | 없음 |
| CR-032 (프로바이더 확장) | 선행 — Bedrock/Vertex 추가했으나 CLI는 미포함 | 이 CR이 보완 |
| CR-030~038 | 참조 — OpenClaude 핵심 메커니즘 포팅 완료로 CLI 세션 관리 우회해도 잃는 것 없음 | 없음 |

**사용자 원문**: "이 설계는 Adapter 레벨이므로 완전 독립. CR-043은 Tool 레벨, CR-044는 Tool 레벨 개선."

---

## 4. 신규/수정 파일 (구체)

### 4-1. 신규 파일
```
backend/platform-core/src/main/java/com/platform/llm/adapter/
  └── ClaudeCliLlmAdapter.java          [신규] LLMAdapter 구현

backend/platform-core/src/main/java/com/platform/llm/claudecli/
  ├── ClaudeCliWorkerPool.java          [신규] run당 워커 풀
  ├── ClaudeCliWorker.java              [신규] 단일 프로세스 래퍼
  └── ClaudeCliAdapterConfig.java       [신규] 타임아웃/최대워커수 설정
```

### 4-2. 수정 파일
- `ConnectionAdapterFactory.java` — `anthropic-cli` 케이스 추가, `normalizeAdapterType()` 매핑
- `WorkflowEngine.java` — Run 시작/종료 훅, `pool.shutdownForRun()` 연결
- `ParallelStepExecutor.java` — 병렬 브랜치 시작 시 pool에 fork 워커 생성 요청
- `application.yml` — `anthropic-cli` 어댑터 설정 섹션

### 4-3. 재활용 (변경 없음)
- `AgentAccountPoolManager.java` — OAuth 계정 풀, CLAUDE_CONFIG_DIR 격리
- `AnthropicAdapter.java` — API Key 경로 유지
- `ClaudeCodeTool.java` — 에이전트 자율 작업용 존속
- SessionManager, 정책 엔진

---

## 5. 미해결 질문 (구현 단계에서 결정)

1. **워커 풀 수명 훅 위치**: WorkflowEngine 직접 vs StepContext 참조
   - 현재 결론: "StepContext가 record라 변경 어려우므로 WorkflowEngine run 시작/종료 이벤트 리스너 권장"

2. **병렬 브랜치 생성 주체**: Adapter 내부 판단 vs ParallelStepExecutor 명시 알림
   - 현재 결론: "후자가 명시적이고 디버깅 쉬움"

3. **num_turns > 1 엣지 케이스**: `--tools ""`인데 여러 턴 돌리는 케이스 감지·차단 방법

4. **UnifiedMessage → stream-json 매핑 상세**:
   - `{"type":"user","message":{...}}` 구체화
   - assistant 메시지, tool_result, system 메시지 처리

5. **첫 턴 vs 이후 턴 구분**: 워커가 처음 받는 요청인지 추적 (풀에서 워커 내부 상태 관리)

6. **향후 도구 지원**: 현재는 빈 구현/예외, 확장 여부 미정

7. **비용 추적 상세**: stream-json에서 usage 추출 가능 여부 검증 필요

---

## 6. KPI / 수치 (세션 중 언급된 전부)

| 메트릭 | 값 | 맥락 |
|---|---|---|
| Node.js 초기화 오버헤드 | ~500ms | per workflow run |
| 10 LLM_CALL 워크플로우 (현재) | 5초 | ClaudeCodeTool 매번 호출 |
| 10 LLM_CALL 워크플로우 (신규) | 0.5초 | 풀 재사용 |
| 기동 오버헤드 감소 | 10배 | 핵심 이득 |
| 병렬 브랜치 캐시 비용 절감 | $0.07 → $0.007 | T4 테스트, 약 10배 |
| API 호출 시간 (지배적) | 3~30초 | 작업 복잡도에 따라 |
| Node.js 오버헤드 비율 | 1~15% | 전체 시간 중 |

---

## 7. 운용 정책 (최종 결정)

- **개인/내부 테넌트 한정** — 외부 상용 서비스에서는 비활성화
- **테넌트별 피처 플래그**로 허용 여부 제어
- **상용화 시점에 Anthropic과 협의**, 정식 허가 조건 하에 운영

**사유**: Claude Max 구독 토큰을 자동화 파이프라인에서 대량 사용하는 것은 ToS 경계선에 있음. 개인용·내부용은 허용, 외부 상용 재판매는 명백한 위반.

---

## 8. 당시 결정된 구현 순서 (제안)

1. `ClaudeCliWorker` — 단일 프로세스 수명, stdin/stdout 파이프, 한 턴 송수신
2. `ClaudeCliWorkerPool` — Run 단위 풀, lazy spawn, shutdown
3. `ClaudeCliLlmAdapter` — `chat(LLMRequest)` 구현, 메시지 변환
4. `ConnectionAdapterFactory` 통합 — `anthropic-cli` 케이스
5. `WorkflowEngine` 수명 훅 — Run 종료 시 pool shutdown
6. 병렬 브랜치 처리 — `spawnForkedWorker(parentSessionId)`
7. 테스트 — 단일 턴, 멀티 턴, 병렬 브랜치, Run 종료 정리
8. 설정/플래그 — 테넌트별 허용 여부 체크

---

## 9. 당시 종료 시점 상태

- ✅ 핵심 설계 확정
- ✅ T3/T4 테스트로 fork-session, 캐시 재사용 검증 완료
- ✅ CLI 기동 커맨드 검증 완료
- ❌ CR 번호 미발번 (당시 CR-045를 예상했으나 실제 CR-045는 "대화형 채팅 UI"가 가져감)
- ❌ 구현 미착수

**사용자 최종 발언**: "위 내용위주로 개발자에게 전달합니다" + "docs/origins에 저장하고 cr 캐스케이드에 따라 구현"

---

## 10. 복원 경로

이 문서는 2026-04-16 세션에서 사용자가 "이 내용 한참 논의했었는데 세션이 사라졌나보네요"라고 회고한 뒤, 메모리·세션 로그 검색으로 복원된 것이다. 별도 CR 인계서는 `CR050_이관인계서_20260416.md` 로 작성 예정.
