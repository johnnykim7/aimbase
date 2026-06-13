# CR-106 설계 — 장기 AGENT_CALL retry 멱등화 (이어하기 + timeout 설정화)

> 상태: **구현 완료** (단위 13 PASS + 회귀 PASS, 빌드/배포 대기) · 작성일 2026-06-14 · 방향 확정 A(retry 멱등화/이어하기) + B(timeout 설정화), C(FOREACH 분할) 제외 — 소비앱 워크플로우 영역
>
> ## 구현 결과 (2026-06-14)
> - **A 멱등화**: `SubagentRequest.resumeSessionId` 신설 + `SubagentRunner.java:113` 분기(있으면 childSessionId 로 사용) + `AgentCallStepExecutor.buildRequest(…, enableResume)` 가 직전 attempt 가 timeout 류일 때만 `deterministicSessionId(runId, stepId)`(=`subagent-` + `UUID.nameUUIDFromBytes`)를 resumeSessionId 로 전달. 단일 AGENT_CALL 한정(멀티는 enableResume=false — 같은 stepId 충돌 회피).
> - **A 전달 채널**: `StepContext.previousAttemptFailure` + `withRetryFailure()` 신설 + `WorkflowEngine.executeWithRetry` 가 catch 에서 `attemptContext = context.withRetryFailure(e.getMessage())` 로 다음 attempt 에 직전 실패 주입.
> - **B 설정화**: `ClaudeCliRunnerClient` HTTP timeout 하드코딩 300s → `@Value platform.llm.anthropic-cli.http-timeout-seconds`(application.yml, 기본 300, ENV ANTHROPIC_CLI_HTTP_TIMEOUT_SECONDS). `RunnerAutoConfiguration` Worker turnTimeout 하드코딩 300s → `RunnerProperties.turnTimeoutSeconds`(aimbase.runner.turn-timeout-seconds, 기본 300).
> - **테스트**: `AgentCallStepExecutorCr106Test` 6 PASS(첫시도 noResume / timeout→결정적resume / 비timeout→noResume / 결정적키 안정·구분 / isTurnTimeoutFailure) + 기존 AgentCallStepExecutorTest 7 + workflow/agent/adapter/cli-runner 회귀 전부 PASS.
> - **step별 동적 turnTimeout 전파는 미채택**: BE→Runner HTTP body 에 timeout 미전달(buildBody 무변경). 양 모듈 걸친 작업이라 범위 초과 — 정적 설정화로 갈음(B 의도 충족). 향후 필요 시 별도.
> 발견 경위: CR-104 e2e (run 9a8704a8 / opportunity-analysis / build_workspace) 중 발견. 원본 `docs/origins/원본_요구사항_CR106_AGENT_CALL_장기작업_timeout_retry멱등_20260614.md`

## 1. 결함 체인 (실측 확정)

```
WorkflowEngine.executeWithRetry (WorkflowEngine.java:870)  ─ retry 3회, 같은 step/context 반복 호출
  └→ AgentCallStepExecutor.execute → buildRequest → SubagentRunner.run
       └→ SubagentRunner.java:113-114  ★ 매 호출마다 무조건 새 UUID 발급
            runId        = UUID.randomUUID().toString()
            childSessionId = "subagent-" + UUID.randomUUID()
       └→ OrchestratorEngine → ClaudeCliRunnerClient.java:103 / :160
            run_id = request.sessionId()  (= childSessionId)
            └→ RunnerService.java:89,98  run_id 별 getOrCreateMain
                 └→ ClaudeCliWorkerPool.getOrCreateMain (Pool.java:91) "같은 runId 면 살아있는 Worker 재사용"
```

3겹 원인:
1. **근본**: build_workspace 가 CLI 에게 첨부 PDF 다수를 parse_document→file_write 적재 지시 → 단일 turn 300초 초과.
2. **타임아웃 2곳 동시**:
   - `ClaudeCliRunnerClient.java:146` HTTP `.timeout(Duration.ofSeconds(300))` — **하드코딩**
   - `ClaudeCliWorker.java:103` `turnTimeout` 기본 300s — 생성자 주입 가능하나 호출 측이 default 전달
3. **★ retry 멱등 아님(핵심)**: `SubagentRunner.java:113-114` 가 retry 마다 새 `childSessionId` 발급 → CLI Runner `run_id`(Worker 재사용 키) 매번 달라 `getOrCreateMain` 이 살아있는 Worker 못 찾고 새 spawn + `--resume` 미사용 → 이전 작업장 적재 맥락 모른 채 30KB 프롬프트부터 다시 → 같은 일 3번 반복. 3번째 turn 도 5분 후 timeout.

## 2. 재사용/이어하기 인프라 — 이미 존재 (미사용)
- Pool 재사용: `ClaudeCliWorkerPool.getOrCreateMain(runId,…)` "같은 runId 면 재사용" (Pool.java:97).
- CLI 이어하기: `ClaudeCliWorker` 생성자 `resumeSessionId` + 명령 빌더 `.resume(resumeSessionId, forkSession)` (Worker.java:686).
- turnTimeout 외부 주입: `ClaudeCliWorker` 생성자 6번째 인자로 이미 받음 (Worker.java:96,103).
- **미사용 단일 원인 = 호출 측 SubagentRunner 가 retry 마다 새 childSessionId 발급.** start 자체는 ~1초로 빠름 — 진짜 손해는 "새 세션이라 적재 작업 0부터 다시".

## 3. 설계 (A + B)

### A. retry 멱등화 — 핵심
retry 시 **같은 step 의 AGENT_CALL 은 같은 childSessionId 를 재사용**해, Pool 이 살아있는 Worker 를 재사용하고 CLI 는 `--resume` 으로 이어한다. 작업장에 이미 적재된 파일 위에 누적되므로 "이어하기"가 실제 효과를 낸다.

**구현 위치 = AgentCallStepExecutor.buildRequest (AgentCallStepExecutor.java:134)** — retry 가 같은 `step.id` + 같은 `workflowRunId` 로 반복 호출되므로, childSessionId 를 이 두 키에서 **결정적으로 파생**한다.

핵심 변경:
- `SubagentRequest` 에 `resumeSessionId`(nullable) 필드 추가 — 호출 측이 명시하면 SubagentRunner 가 그 값을 childSessionId 로 사용, 없으면 기존대로 새 UUID.
- `AgentCallStepExecutor.buildRequest` 가 `(workflowRunId, stepId)` 로 결정적 childSessionId 생성:
  `"subagent-" + UUID.nameUUIDFromBytes((workflowRunId + ":" + stepId).getBytes())`
  → 같은 step 의 retry 는 항상 같은 sessionId → Pool 재사용 + `--resume` 자동 적용.
- `SubagentRunner.java:113-114` 분기: `request.resumeSessionId()` 있으면 그것을 childSessionId 로, 없으면 기존 새 UUID (메인 대화 서브에이전트 = 매번 새 세션 그대로 유지 → [[project_context_isolation_architecture]] 격리 설계와 충돌 없음. **워크플로우 AGENT_CALL 경로에서만** 결정적 키 적용).

> **확정 (2026-06-14 사용자 결정)**: **timeout 류 실패만 이어하기**. 그 외 사유(잘못된 프롬프트 등) retry 는 새 세션으로 깨끗이 재시도.
> 구현: AgentCallStepExecutor 가 직전 attempt 실패가 timeout 류(TIMEOUT status / "turn timeout" 메시지)였을 때만 resumeSessionId 전달. 단, 결정적 childSessionId 는 step·run 으로 파생되므로, timeout 류일 때만 그 키를 "이어할 세션"으로 넘기고 그 외엔 null(새 UUID) → Pool 이 새 Worker spawn.

### B. timeout 설정화 — 완화
- `ClaudeCliRunnerClient.java:146` HTTP timeout 하드코딩 300s → 설정값 주입.
- AGENT_CALL step config 의 `timeout_ms`(AgentCallStepExecutor.java:147, 현재 기본 120s) 가 **CLI turnTimeout + HTTP timeout 까지 일관되게 전파**되도록 연결 (현재는 SubagentRequest.timeoutMs 만 채우고 CLI turnTimeout/HTTP 와 단절).
- application.yml `platform.llm.anthropic-cli.*` 에 기본 turn/HTTP timeout 외부화 키 추가.

> B 단독은 미봉책 — 상향해도 retry 가 멱등이 아니면 매 retry 가 길어진 timeout 을 처음부터 다시 소모. A 와 함께여야 효과.

### A-1. retry 간 직전 실패 사유 전달 (구현 메커니즘)
`executeWithRetry`(WorkflowEngine.java:870)는 같은 `step`/`context` 로 반복 호출하고 직전 예외를 executor 에 넘기지 않는다. `StepContext` 는 record 라 채널 없음.
**정공**: retry 를 관장하는 WorkflowEngine 이 "직전 attempt 의 실패 사유"를 executor 에 흘려보낸다.
- `StepExecutor.execute(step, context)` 시그니처는 유지하되, `StepContext` 에 nullable `previousAttemptFailure`(직전 예외 메시지/status) 필드 추가 + `withRetryFailure(...)` 헬퍼.
- `executeWithRetry` 의 catch 블록에서 다음 attempt 용 context 를 `context.withRetryFailure(e)` 로 만들어 재호출.
- `AgentCallStepExecutor.buildRequest` 가 `context.previousAttemptFailure()` 가 timeout 류일 때만 결정적 childSessionId 를 `resumeSessionId` 로 전달, 아니면 null.
- timeout 류 판정: 직전 예외 메시지에 `"turn timeout"` 포함 또는 SubagentResult.Status.TIMEOUT (AgentCallStepExecutor.java:83 이 이미 `"AGENT_CALL failed: " + reason` 으로 승격하므로 reason 에 turn timeout 문자열 보존됨).

## 4. 변경 파일 (예상)
| 파일 | 변경 |
|------|------|
| `SubagentRequest.java` | `resumeSessionId`(nullable) 필드 추가 |
| `SubagentRunner.java:113-114` | resumeSessionId 있으면 childSessionId 로 사용 분기 |
| `StepContext.java` | nullable `previousAttemptFailure` + `withRetryFailure(...)` 헬퍼 |
| `WorkflowEngine.java:870` (executeWithRetry) | catch 에서 다음 attempt context 에 직전 실패 주입 |
| `AgentCallStepExecutor.java:134` (buildRequest) | timeout 류 직전 실패 시 (runId, stepId) 결정적 childSessionId 를 resumeSessionId 로 전달 + timeout 전파 |
| `ClaudeCliRunnerClient.java:146` | HTTP timeout 설정값화 |
| `application.yml` | anthropic-cli timeout 키 추가 |
| 테스트 | SubagentRunner / AgentCallStepExecutor retry 멱등 단위 테스트 (timeout 류 → 같은 sessionId, 그 외 → 새 sessionId) |

## 5. 설계 확정값 (2026-06-14 사용자 결정)
1. **이어하기 적용 범위**: timeout 류 실패만 이어하기, 그 외는 새 세션. ✅ 확정
2. **결정적 키 적용 경로**: 워크플로우 AGENT_CALL 한정. ✅ 확정 (메인 대화 서브에이전트 격리 유지)
3. **BIZ 규칙**: 구현 시 "AGENT_CALL timeout retry 는 동일 step·run 에서 CLI 세션을 --resume 이어한다" BIZ 신설 — 번호는 구현 착수 시 CR_변경_이력 최신 BIZ 확인 후 부여.

## 관계
[[project_cr104_status]] e2e 의 ★별개 이슈. CR-104(도구 노출) 결함 아님. [[project_cr106_status]] 발번 메모.
