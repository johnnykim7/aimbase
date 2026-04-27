# 원본 요구사항 — CR-070 ClaudeCodeTool 실시간 스트리밍 중계 보강

- **일자**: 2026-04-27
- **요청자**: sykim (대화)
- **CR**: CR-070
- **관련 CR**: CR-042 (aimbase-agent 독립 모듈), CR-044 (CLI 두뇌 + Aimbase 손발), CR-050 (Claude CLI → LLM 어댑터), CR-053 (서브에이전트 UX SSE), CR-067/068 (어댑터 동등화)

---

## 발단

대화에서 Aimbase의 Claude 사용 3가지 방식 비교 중 정액 플랜 ToS 경계를 짚으면서 정리된 사항.

### 3가지 방식
- **(1) Claude API** (AnthropicAdapter) — Anthropic REST API 직접 호출, 토큰 과금
- **(2) Claude CLI 어댑터** (ClaudeCliLlmAdapter, CR-050) — CLI 두뇌 + Aimbase MCP 도구
- **(3) ClaudeCodeTool** — CLI 두뇌 + (기본) CLI 본인 도구, `tool_bridge`로 Aimbase MCP 선택 가능

### ToS 경계 정리
- (2)를 서버에서 LLM_CALL 백엔드로 외부 테넌트에 노출하면 "정액 플랜을 API 대체재로 재판매"하는 모양 → ToS 위반 소지
- (3)은 사용자 PC aimbase-agent에서 실행되므로 "사용자 본인이 Claude Code를 쓴다"가 사실 그대로 → ToS 안전

### 결정
- (2)는 내부 개발/벤치마크 전용으로 격리 (상용화 시점에 BIZ-099 피처 플래그 명문화 — 이번 세션에서는 안 건드림)
- (3)이 표준 경로
- (3)에 이미 `tool_bridge: aimbase-mcp-only` 모드가 있어 "(2)의 취지(Aimbase Tool 사용)"가 (3)에서 그대로 달성 가능 (CR-044에서 구현됨)

---

## 사용자 PC Agent 실행 모델

```
사용자 → Aimbase 서버 (지시 전달) → Agent [LLM(CLI) + Aimbase Tool 호출 모두 여기서] → 진행상황을 서버로 push → 서버가 SSE로 사용자에게 중계
```

- Agent가 오케스트레이터, 서버는 중계자
- 사용자 PC가 꺼져있을 일 없음 (사용자가 자기 PC에서 명령), 멀티 Agent 라우팅도 고려 대상 아님
- 통제권은 (2)와 동일 (CLI 두뇌가 도구 루프 자율 통제)

---

## 갭 분석 (소스 직접 확인)

**잘 되어 있는 부분:**
- 서버 → 사용자 SSE 채널 완성 (`ChatController.java:142-227`)
- `SubagentRunner`의 `STREAM_SINK` ThreadLocal 패턴 (`SubagentRunner.java:45-64`)
- ClaudeCliWorker는 라인 단위 NDJSON 스트림 처리 (`ClaudeCliWorker.java:521-540`)

**끊기는 지점:**
- `ClaudeCodeTool.java:448-455` — stdout을 라인 단위가 아니라 통째로 누적 → 중간 이벤트 폐기
- `ClaudeCodeTool.java:492-493` — `--output-format stream-json` 옵션은 지원하나 마지막에 한 번 파싱
- Agent → 서버 진행 이벤트 push 채널 없음 (현재 동기 응답만)

---

## 작업 범위

### Phase A — 로컬 서버 실행 케이스 (당장 가치 큼)
1. ClaudeCodeTool stdout 라인 단위 스트림 처리 (ClaudeCliWorker 패턴 차용)
2. `STREAM_SINK` ThreadLocal 패턴을 ClaudeCodeTool에도 적용 (SubagentRunner와 동일)

### Phase B — Agent 실행 케이스
3. Agent → 서버 push 채널 추가 (Agent ↔ 서버 프로토콜 설계 수반)

### 후순위
- 토큰 사용량·도구 호출 감사 로깅 보강

---

## 사용자 메시지 발췌

> "사실 3을 사용하게되면 2를 사용하게됬을경우와 비교해서 통제권이라든가. 뭐 그런게 달라질게 있나요? 동일한거 아닌가요? 그냥 두뇌만 원격에 있는거잖아요"

> "그런데. 이제부터가 문제가되는 부분인데요.. 가만히 살펴보면 알겠지만 둘간의 사용처에 따라 클로드의 라이센스 정책에 걸리는 부분이 발생합니다."

> "3)은 사용자가 자기 피씨에서 사용하도록 할거거든요"

> "그럼 결국.. 원래 2)를 만든 취지가 3)을 aimbase tool을 사용하게끔하려고 한거잖아요.. 그럼 3)을 aimbase툴을 사용하게하면. 취지가 완성디는거 아닌가해서"

> "(2) Claude CLI 어댑터 격리 — 이건 그냥 메모리에만 넣어주세요 나중에 상용화되면 격리해야함."

> "(3) 스트리밍 보강 — 이건 메모리에 저장." (이후 우선순위 변경: "3번이 최우선 그리고 1번")

---

## 후속 작업 별건 (이번 CR 범위 외)

- **Agent의 Claude 키 인지 문제** — Agent가 사용자 PC의 `claude login` 토큰을 못 읽는 이슈. CR-070 다음 작업으로 진행 예정.
- **(2) Claude CLI 어댑터 격리** — `project_cli_adapter_isolation.md` 메모리에 저장됨. 상용화 시점에 BIZ-099 피처 플래그 명문화 처리.
