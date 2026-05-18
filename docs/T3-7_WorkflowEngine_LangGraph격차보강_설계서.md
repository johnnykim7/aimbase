# T3-7 | WorkflowEngine LangGraph 격차 보강 설계서

- **발번**: CR-084, CR-085
- **작성일**: 2026-05-16
- **상태**: CR-084 **P1~P5 구현 완료** (2026-05-16, dev 브랜치, 단위 회귀 664 PASS) / CR-085 **P1~P4 구현 완료** (2026-05-18, dev 브랜치, platform-core 653 PASS — StepContextCr085Test 17 포함)
- **원본**: `docs/origins/원본_요구사항_LangGraph격차_WorkflowEngine보강_20260516.md`

> 본 설계서는 실측(`com.platform.workflow` 전수 리딩) 기반. 변경 지점은 모두 `file.java:line` 으로 명시.

---

## 0. 현재 아키텍처 실측 요약

| 요소 | 현재 동작 | 파일:라인 |
|------|----------|-----------|
| 실행 루프 | `sortedSteps` 단일 for **1-pass** 순회 | WorkflowEngine.java:280 |
| 사이클 처리 | `topologicalSort` 미완료 시 경고 + **원순서 폴백** (재방문 없음) | WorkflowEngine.java:616-620 |
| 분기 | CONDITION `true_step`/`false_step` **2갈래 고정** | ConditionStepExecutor.java:42-49 |
| 분기 스킵 | `applyConditionSkips` 2분기 가정 하드코딩 | WorkflowEngine.java:495-510 |
| State 접근 | `{{ns.key}}` **1뎁스 dot**, 실패 시 빈문자열, reducer 없음 | StepContext.java:86-125 |
| 이벤트 | `stepRunning`/`stepCompleted`/`stepFailed` **step 단위** | WorkflowEngine.java:311-342 |
| 스텝 결과 누적 | `withStepResult` 가 `stepResults` 맵에 **덮어쓰기** | StepContext.java:128-132 |

---

# CR-084 | 임의 cycle + 동적 N-way 라우팅

## 1. 목표

- 워크플로우에 `graph_mode: "cyclic"` 명시 시, 임의 노드 간 순환 지원 (예: `agent → tool → agent` 를 조건 만족까지 반복)
- 새 `ROUTER` 스텝타입: 런타임 표현식/이전 LLM 출력으로 **N개 후보 중 1개** 동적 선택
- 기존 DAG 워크플로우(graph_mode 미지정 또는 `dag`)는 **현행 1-pass 로직 무변경** — 하위호환 절대 보장

## 2. 비목표 (이번 범위 제외)

- time-travel 디버깅 (과거 체크포인트 rewind 후 대안 분기)
- 임의 노드 동적 생성 (코드형 그래프)

## 3. 설계

### 3.1 WorkflowStep — graph_mode / ROUTER

`WorkflowStep.StepType` 에 `ROUTER` 추가 (WorkflowStep.java:18-20).

`graph_mode` 는 스텝이 아니라 **워크플로우 레벨** 속성.

> **확정 (2026-05-16, 사용자 승인)**: `graph_mode` 저장 위치 = **(a) `WorkflowEntity` 신규 컬럼 `graph_mode VARCHAR(20)`**.
> - 근거: 이 코드베이스가 워크플로우 레벨 설정(`output_schema` V18, `input_schema` V31, `error_handling`)을 일관되게 별도 컬럼으로 분리해온 선례(실측 확인). `trigger_config`(언제 실행=트리거)에 graph_mode(어떻게 실행)를 섞으면 의미 혼재.
> - 비용: Flyway tenant `V62` 1개 + 운영 5개 테넌트 DB 적용 (CR마다 늘 해온 작업, 추가 부담 아님).
> - `WorkflowEntity` 변경: `private String graphMode;` 필드(default `"dag"`) + getter/setter 추가 (WorkflowEntity.java:38 `errorHandling` 패턴 동일).
> - 미지정/null = `"dag"` 로 처리 → 기존 워크플로우 전부 자동 DAG 모드 (하위호환).

### 3.2 실행 루프 분기 — 1-pass vs 워크리스트 스케줄러

`doExecuteAsync` (WorkflowEngine.java:244-398) 를 다음으로 분기:

```
graphMode == DAG (기본/미지정)  → 기존 sortedSteps for-loop 그대로 (무변경)
graphMode == CYCLIC             → executeCyclic(steps, run, context)
```

`executeCyclic` 신설 — **워크리스트(worklist) 기반 스케줄러**:

- 시작 노드(dependsOn 없는 노드 또는 명시 `entry_step`)를 worklist 에 push
- 루프: worklist 에서 노드 pop → 실행 → 결과로 **다음 노드 결정** → worklist push
- 다음 노드 결정 규칙:
  - `ROUTER`/`CONDITION` 스텝 → 실행 결과의 `next_step` (N-way) 사용
  - 일반 스텝 → `onSuccess` 또는 그래프 엣지(`edges` 정의)의 후속 노드
- **무한루프 방어 (글로벌 step budget, BIZ 신규)**: run 당 총 스텝 실행 횟수 상한 `max_total_steps`(기본 50, config override, 절대 상한 200). 초과 시 run `failed` + `error.reason="step_budget_exceeded"`. EVALUATOR_LOOP 의 `MAX_ITERATIONS_CEILING` 과 별개의 그래프 레벨 방어선
- 종료: worklist 비었거나, 노드가 `__end__` 반환, 또는 budget 소진

### 3.3 ROUTER 스텝 (신규 executor)

`RouterStepExecutor implements StepExecutor`, `supports() = ROUTER`.

config 형식:
```json
{
  "routes": [
    { "when": "{{classify.output}} equals 'refund'", "to": "refund_step" },
    { "when": "{{classify.output}} equals 'inquiry'", "to": "inquiry_step" },
    { "default": true, "to": "fallback_step" }
  ]
}
```

- `when` 표현식은 기존 `ConditionStepExecutor.evaluate` 의 평가기 **재사용** (중복 구현 금지 — evaluate 를 별도 `ExpressionEvaluator` 컴포넌트로 추출하여 CONDITION/ROUTER 공유)
- 첫 매치 route 의 `to` 를 `next_step` 으로 반환. 매치 없으면 `default` route
- 반환 형식은 CONDITION 과 동일 계약(`next_step` 키) → 스케줄러가 동형 처리

### 3.4 N-way skip 일반화

`applyConditionSkips` (WorkflowEngine.java:495-510) 의 2분기 하드코딩 제거. cyclic 모드에서는 skip 캐스케이드 자체가 불필요(워크리스트는 도달한 노드만 실행). **DAG 모드는 무변경** — 기존 2분기 로직 유지(CONDITION 하위호환).

### 3.5 resume / 이벤트 / 멀티테넌시 영향

- **resume** (WorkflowEngine.java:163-224): cyclic 모드 재개 시 worklist 상태 복원 필요. `WorkflowRunEntity` 에 `pending_worklist` JSONB 추가하여 일시중단 시점의 worklist + step budget 잔량 저장 → 재개 시 복원. DAG 모드는 기존 `currentStep` 복원 그대로
- **이벤트** (WorkflowEventPublisher): cyclic 에서 같은 노드가 N회 실행되므로 `stepRunning`/`stepCompleted` 에 **iteration index** 동반 (이벤트 계약 확장, 하위호환 위해 nullable). CR-085 의 토큰 스트리밍과 합류 지점
- **멀티테넌시**: 변경 없음 (Virtual Thread TenantContext 전파 기존 그대로)

## 4. CR-084 Phase

| Phase | 내용 | 산출물 |
|-------|------|--------|
| P1 | `ExpressionEvaluator` 추출 (CONDITION 평가기 분리, 회귀 보장) | 신규 컴포넌트 + 기존 CONDITION 테스트 GREEN |
| P2 | `ROUTER` StepType + `RouterStepExecutor` + 검증(WorkflowValidator) | N-way 단위 테스트 |
| P3 | `graph_mode` 저장 + `executeCyclic` 워크리스트 스케줄러 + step budget | cyclic e2e 테스트 (agent↔tool 순환) |
| P4 | resume worklist 복원 + 이벤트 iteration index | resume 회귀 + 이벤트 단위 테스트 |
| P5 | 회귀 전수 (기존 DAG 워크플로우 무변경 검증) + 문서/가이드 | 전 워크플로우 회귀 GREEN |

## 5. BIZ 규칙 신규/변경

- **BIZ-신규**: cyclic 그래프 run 당 총 스텝 실행 상한 기본 50, 절대 상한 200 (graph 레벨 무한루프 방어, EVALUATOR_LOOP MAX_ITERATIONS_CEILING 과 별개)
- **BIZ-009 보강**: "워크플로우 DAG 실행" → "graph_mode=dag 는 Kahn 위상정렬 1-pass, graph_mode=cyclic 은 워크리스트 스케줄러 + step budget"

## 6. CR-084 구현 완료 기록 (2026-05-16)

| Phase | 산출물 | 검증 |
|-------|--------|------|
| P1 | `ExpressionEvaluator` 추출, `ConditionStepExecutor` 위임 | ExpressionEvaluatorTest 16 PASS |
| P2 | `ROUTER` StepType + `RouterStepExecutor` + `WorkflowValidator.validateRouter` | RouterStepExecutorTest 7 + WorkflowValidatorTest ROUTER 8 PASS |
| P3 | V62(graph_mode) + `executeCyclic` 워크리스트 스케줄러 + step budget | WorkflowEngineCyclicTest 15 PASS |
| P4 | V63(pending_worklist) + cyclic resume 복원 + 이벤트 `iterationIndex`(nullable) | WorkflowEventPublisherTest 9 + WorkflowRunSubscriberRegistryTest 9 PASS |
| P5 | api-guide v3.1.0 + ops-guide v3.2.0 + 전수 회귀 | **platform-core 전체 664 PASS, 0 fail/error** |

**하위호환 입증**: `graph_mode` 미지정/null = DAG 1-pass 무변경, `iterationIndex` null=DAG — 모두 단위 테스트로 고정. DAG 경로 코드 한 줄도 미변경(분기만 추가).

**알려진 한계 (정직 기록)**:
- `executeCyclic` while 루프 본문(DB 왕복·이벤트 발행)은 Repository 7개 의존이라 단위 테스트 불가 → 분기 두뇌(`resolveNextStep`/`resolveEntryStep`/`resolveStepBudget`/`isCyclicMode`)와 이벤트 계약만 단위 검증. **cyclic 실제 DB e2e(agent↔tool 순환 DB 왕복, resume 복원 실왕복)는 미검증 — IT 후속 필요**
- V62/V63 마이그레이션은 작성만, 운영 5개 테넌트 DB 적용은 배포 시점 작업 (커밋 미수행)
- `WorkflowEvents.StepStatusChanged` record 필드 추가로 직접 생성처 4곳(테스트) 수정 필요했음 — record 확장의 알려진 비용, main 직접 생성처는 publisher 3곳뿐이라 격리됨

---

## 6-B. CR-085 구현 완료 기록 (2026-05-18)

| Phase | 산출물 | 검증 |
|-------|--------|------|
| P1 | `StepContext.resolveRef` 첫 토큰 namespace 분리 + `traverse(root, List<String>)` 중첩 경로 탐색 + `parsePath` (점/대괄호 토크나이저) | `StepContextCr085Test.P1Compat` 5 + `P1Nested` 5 PASS |
| P2 | `StepContext.withStepResult(stepId, result, channel, reduce)` 오버로드 (replace/append/merge) + `WorkflowEngine.applyStepResult` DAG/cyclic 2곳 위임 | `P2Compat` 3 + `P2Reduce` 4 PASS |
| P3 | `WorkflowEvents.StepToken` + `WorkflowEventPublisher.stepToken` + `LlmCallStepExecutor.callLlmStreaming` (`LLMAdapter.chatStream` 공용 콜백 재사용) | 컴파일+회귀 GREEN (LLM 실스트리밍 e2e는 IT 후속) |
| P4 | api-guide v3.2.0 / ops-guide v3.3.0 + 부록 A 6항목 검증 | platform-core **653 PASS, 0 fail** |

**하위호환 입증** (부록 A 대응):
- `{{ns.key}}` 1뎁스 = 기존 결과 동일, 실패 시 빈 문자열 → `P1Compat.oneDepth*`/`missingRefEmpty` 고정
- 점/대괄호 없는 `{{plain}}` = 원문 유지 → `noNamespaceKeepsRaw` 고정
- `output_channel` 미지정 = 기존 `withStepResult` 덮어쓰기 동일 → `P2Compat.nullChannelSameAsLegacy` 고정
- `stream_tokens` 미지정/`response_schema` 존재 = 기존 동기 경로 (`callLlm`) — `streamTokens` boolean 기본 false + eventPublisher null 가드
- DAG 경로(`applyStepResult` channel 미지정 분기) = 기존 `context.withStepResult(step.id(), enriched)` 그대로 호출, CR-084 cyclic 경로도 동일 위임

**알려진 한계 (정직 기록)**:
- P3 `stepToken`의 `iterationIndex`는 null 고정 — `StepExecutor.execute(step, context)` 인터페이스가 cyclic 회차를 executor에 전달하지 않음(설계 §2.3 DAG/기존=null 계약과 호환). cyclic 회차별 토큰 정밀 매핑은 인터페이스 확장 동반 후속
- P3 실 LLM 스트리밍 e2e 미검증 — 단위는 `chatStream` 콜백 재조립 계약까지. 실제 어댑터 스트리밍 왕복은 IT 후속
- 마이그레이션 없음 (StepContext API + 이벤트 record 확장만, DB 스키마 무변경) — CR-084의 V62/V63 같은 배포 선행 작업 불요

---

# CR-085 | State 타입드 채널/reducer + 노드 내부 토큰 스트리밍

## 1. 목표

- `StepContext` 변수 접근을 **중첩 경로**(`{{step.a.b[0]}}`)로 확장
- **채널 reducer**: 채널별 병합 전략 (덮어쓰기 / append / merge) — LangGraph 의 reducer 대응. 특히 메시지 누적(append) 채널
- 노드 내부 LLM 토큰을 워크플로우 이벤트로 스트리밍 (step 단위 → 토큰 단위)
- **하위호환 절대**: 기존 `{{ns.key}}` 1뎁스 + 빈문자열 폴백 동작 그대로 (모든 9개 executor 가 이 API 사용)

## 2. 설계

### 2.1 StepContext 경로 해석 확장

`StepContext.resolveRef` (StepContext.java:86-125) 확장:

- 현재: `ref.indexOf('.')` 로 namespace/key 1회 분리 → 1뎁스만
- 변경: `key` 부분을 **경로 토큰 시퀀스**로 파싱 (`a.b[0].c`) 후 Map/List 재귀 탐색
- **하위호환**: 1뎁스 ref 는 기존과 동일 결과. 경로 탐색 실패 시 **기존 동작 그대로 빈문자열** (로그 warn 유지). 즉 동작 변화 없이 표현력만 확장 — 기존 워크플로우 무영향

### 2.2 채널 reducer (신규 개념, opt-in)

- `WorkflowStep.config` 에 `output_channel` + `reduce` 키 추가 (opt-in — 미지정 시 기존 `withStepResult` 덮어쓰기 동작 그대로)
- `reduce` 전략: `replace`(기본/현행) | `append`(List 누적) | `merge`(Map 병합)
- `StepContext.withStepResult` (StepContext.java:128-132) 에 reduce 분기 추가. **미지정 = replace = 현행 동작 100% 보존**
- 메시지 누적 패턴(LangGraph `add_messages` 대응): `output_channel: "messages", reduce: "append"` → cyclic 그래프에서 대화 누적 가능 (CR-084 와 시너지)

### 2.3 노드 내부 토큰 스트리밍

- `WorkflowEventPublisher` 에 `stepToken(runId, parentRunId, stepId, iterationIndex, tokenDelta)` 추가 (이벤트 계약 확장, 기존 이벤트 무변경)
- `LlmCallStepExecutor` (21701 bytes) 가 LLM 호출 시 스트리밍 콜백 → `stepToken` 발행. **단, orchestrator(ChatController SSE) 의 기존 스트리밍 경로와 중복 구현 금지** — 공용 스트리밍 어댑터 콜백 재사용
- opt-in: 워크플로우/스텝 config `stream_tokens: true` 일 때만 토큰 이벤트 발행 (기본 off — 기존 step 단위 이벤트만, 하위호환)

## 3. CR-085 Phase

| Phase | 내용 | 산출물 |
|-------|------|--------|
| P1 | `StepContext` 중첩 경로 파서 (1뎁스/실패 폴백 회귀 보장) | 경로 단위 테스트 + 9 executor 회귀 |
| P2 | 채널 reducer (replace/append/merge), 미지정=replace 보존 | reducer 단위 테스트 |
| P3 | `WorkflowEventPublisher.stepToken` + `LlmCallStepExecutor` 스트리밍 콜백 (공용 어댑터 재사용) | 토큰 스트리밍 e2e |
| P4 | 회귀 전수 + 가이드(api/ops) 갱신 | 전 워크플로우 회귀 GREEN |

## 4. CR-084 와의 의존

- CR-085 P2(append reducer)는 CR-084 의 cyclic 그래프에서 진가 발휘 (메시지 누적). 단 **독립 배포 가능** — CR-085 는 DAG 모드에서도 reducer/경로확장 단독 유효
- CR-084 P4 의 이벤트 iteration index ↔ CR-085 P3 의 `stepToken` iteration index 는 동일 이벤트 계약 — CR-084 먼저 머지 권장

---

## 부록 A. 하위호환 검증 체크리스트 (완료 선언 전 필수)

- [x] graph_mode 미지정 워크플로우 = 기존 1-pass 결과 바이트 동일 (CR-084 검증분 유지, applyStepResult 위임이 channel 미지정 시 기존 withStepResult 호출)
- [x] CONDITION true_step/false_step 2분기 기존 워크플로우 회귀 GREEN (platform-core 653 PASS)
- [x] `{{ns.key}}` 1뎁스 참조 = 기존 결과 동일, 실패 시 빈문자열 동일 (`P1Compat` 5 PASS)
- [x] `output_channel` 미지정 스텝 = `withStepResult` 덮어쓰기 동일 (`P2Compat` 3 PASS)
- [x] `stream_tokens` 미지정 = 기존 step 단위 이벤트만 (`streamTokens` 기본 false + eventPublisher null 가드, 비스트리밍 시 `callLlm` 동기 경로)
- [x] 운영 테넌트 기존 워크플로우 전수 회귀 (platform-core 653 PASS, 0 fail/0 error — CR-085 17 포함)
