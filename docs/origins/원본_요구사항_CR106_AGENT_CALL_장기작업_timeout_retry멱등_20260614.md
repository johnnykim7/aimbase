# 원본 요구사항 — CR-106 AGENT_CALL 장기작업 timeout + retry 멱등화

> 출처: CR-104 e2e 검증 중 발견 → 사용자 질의 대화 (2026-06-14)

## 발견 경위
CR-104(CLI 도구 노출 동일화) 운영 e2e 에서 opportunity-analysis 워크플로우(run 9a8704a8)를 재실행.
build_workspace 스텝(AGENT_CALL, cli-runner-bidding-001 커넥터)에서 CLI 가 도구를 정상 호출(parse_document/file_write/bash)했으나 run 최종 `failed`.

## 사용자 발언 (요지)
1. (실패 원인 질의) "타임아웃은 왜 발생한걸까요?"
2. "각 turn 이 새로 ClaudeCliWorker start 합니다 — 같은 작업을 처음부터 매번 다시 시작합니다. 그리고 30KB 프롬프트를 매번 전송합니다 → 매번 start 하면 시간이 많이 걸리지 않나요? 떠있는 상태에서는 안되나요? 이어서 한다던가.. 옵션이 있을거 같은데."
3. "우선 발번은 해주세요."

## 실측 결론 (코드/운영 로그 직접 확인)
3겹 문제:

### (1) 근본 — 작업량이 300초 초과
build_workspace 프롬프트가 CLI 에게 "첨부 PDF 다수를 parse_document 로 읽어 file_write 로 작업장에 텍스트 저장"을 지시.
첫 turn 로그(22:46:41~22:48:23): parse_document/file_write/bash 활발 호출. PDF 가 많아 단일 turn 300초 안에 미완료.

### (2) 타임아웃 한계 2곳에 300초 박힘 — 동시 발동
- Worker: `ClaudeCliWorker.turnTimeout` 기본 300s → `ClaudeCliTimeoutException: turn timeout after 300s`
- BE→agent HTTP: `ClaudeCliRunnerClient.java:146` `.timeout(Duration.ofSeconds(300))` → `HttpTimeoutException: request timed out` → `cli_runner_unreachable:AGENT_TIMEOUT`

### (3) retry 가 멱등 아님 — 매번 처음부터 (핵심)
- `WorkflowEngine.executeWithRetry` 가 AGENT_CALL 3회 retry.
- 매 retry 마다 `SubagentRunner.java:113-114` 가 **무조건** `runId = UUID.randomUUID()` + `childSessionId = "subagent-" + UUID.randomUUID()` 발급.
- 이 runId 가 CLI Runner `run_id`(= ClaudeCliWorkerPool 의 Worker 재사용 키)로 전달.
- runId 가 매번 달라 `getOrCreateMain` 이 "살아있는 Worker" 못 찾음 → 매번 새 Worker spawn (운영 로그에 `subagent-9d6e41e2…`, `subagent-73630664…`, `subagent-0b24c022…` 3개 서로 다른 runId 확인).
- CLI `--resume` 미사용 → 새 세션이라 이전에 이미 적재한 작업장 파일들을 모른 채 30KB 프롬프트부터 다시 시작.
- 결과: 같은 일을 3번 반복. 세 번째 turn 은 5분간 도구 호출 0건 후 그대로 timeout.

## 중요 사실 — 재사용/이어하기 인프라는 이미 존재
- Worker 재사용: `ClaudeCliWorkerPool.getOrCreateMain(runId, …)` 이 "같은 runId 면 살아있는 Worker 재사용"하도록 이미 구현 (`if (rw.main != null && rw.main.isAlive()) return rw.main`).
- CLI 세션 이어하기: `ClaudeCliWorker` 에 `--resume <sessionId> --fork-session` (resumeSessionId 필드) 이미 구현.
- 미사용 원인: 호출 측(SubagentRunner)이 retry 마다 새 ID 를 주는 것. start 자체는 빠름(~1초). 진짜 손해는 start 가 아니라 "새 세션이라 작업을 0부터 다시" 하는 것.

## 해결 방향 후보 (미착수 — 설계 시 사용자 결정)
- **A. retry 멱등화**: timeout 류 실패는 같은 runId/sessionId 로 `--resume` 이어하기 → 이미 적재된 작업장 위에서 누적 진행. 또는 timeout 류는 "재시도해도 또 timeout"이라 retry 제외.
- **B. timeout 상향·설정화**: turnTimeout / HTTP timeout 을 작업 성격에 맞게 상향 또는 커넥터/스텝 설정으로 외부화.
- **C. 작업 쪼개기**: build_workspace 를 PDF 당 분할(FOREACH 등)해 각 turn 을 짧게.

## 범위 경계
- 이것은 CR-104(도구 노출) 결함이 **아님**. CR-104 덕에 도구가 정상 작동하게 되어 비로소 드러난 다음 단계 문제.
- 메모리 [project_context_isolation_architecture] 의 "서브에이전트는 독립 childSessionId, 결과만 반환" 설계와 retry 멱등성이 충돌하는 지점.
