# 원본 요구사항 — 에이전트 구조적 사고 체계 (Plan Mode + Todo + Task)

> **출처**: openclaude(Claude Code CLI 오픈소스) vs Aimbase 전수 비교 — 2026-04-07 갭 분석 결과
> **작성일**: 2026-04-08
> **CR**: CR-033

---

## 배경

openclaude 전수 비교에서 식별된 **높은 우선순위 Tool 8개** 중 LLM 에이전트의 구조적 사고를 지원하는 핵심 도구 3그룹이 Aimbase에 미구현 상태이다.

1. **Plan Mode (계획 모드)**: LLM이 "바로 코드 쓰기" 대신 "탐색 → 계획 수립 → 실행 → 검증" 사이클을 구조적으로 수행
2. **Todo (세션 체크리스트)**: LLM이 자신의 작업을 세션 내에서 추적·관리하는 체크리스트
3. **Task (백그라운드 작업 관리)**: 장시간 실행 태스크의 생명주기 관리 (생성 → 실행 → 완료/중지)

→ 이 3개가 없으면 복잡한 요청에서 에이전트 출력 품질이 구조적으로 제한됨. 특히 멀티스텝 워크플로우와 서브에이전트 시나리오에서 치명적.

---

## 1. Plan Mode — 구조적 사고 사이클

### openclaude 구현 분석

- **EnterPlanModeTool**: 계획 모드 진입. 읽기 전용 탐색만 허용 (파일 쓰기/편집 불가)
  - plan_id 생성, 초기 plan 구조 저장 (goals, steps, constraints)
  - 세션에 `planModeActive: true` 플래그 설정
  - ToolFilterContext가 `readOnlyMode()` 활성화 → 쓰기 도구 차단
- **ExitPlanModeTool**: 계획 모드 종료. 최종 계획 확정
  - plan summary 작성, `planModeActive: false` 복원
  - 쓰기 도구 재활성화
- **VerifyPlanExecutionTool**: 계획 대비 실행 결과 검증
  - 계획의 각 step vs 실제 실행 결과 비교
  - 미완료/이탈 항목 식별 + 완료율 산출
  - 검증 결과를 plan에 기록

### Aimbase 구현 방향

- **도메인 모델**: `PlanEntity` (session_id 기반 스코핑)
  - `plan_id`, `session_id`, `title`, `status` (PLANNING/EXECUTING/VERIFYING/COMPLETED/ABANDONED)
  - `goals` (JSONB), `steps` (JSONB — 각 step에 id/description/status/result)
  - `constraints`, `verification_result`
- **ToolFilterContext 연동**: planModeActive 시 `readOnlyMode()` 강제
- **ToolCallHandler 연동**: 계획 모드에서는 쓰기 도구 호출 시 자동 거부 + "계획 모드에서는 사용 불가" 메시지

### FSM 상태 전이

```
PLANNING ──(exitPlanMode)──> EXECUTING ──(verifyPlan)──> VERIFYING ──> COMPLETED
    │                            │                            │
    └──(abandon)──> ABANDONED    └──(re-plan)──> PLANNING    └──(fail)──> EXECUTING
```

---

## 2. Todo — 세션 체크리스트

### openclaude 구현 분석

- **TodoWriteTool**: 단일 도구로 CRUD 통합
  - `todos` 배열을 통째로 받아 덮어쓰기 (incremental이 아닌 전체 교체)
  - 각 todo: `{ content, status: "pending"|"in_progress"|"completed", activeForm }`
  - 세션 스코프: 세션 종료 시 자동 소멸
  - 용도: LLM이 멀티스텝 작업에서 자기 진행 상태를 추적

### Aimbase 구현 방향

- **도메인 모델**: `TodoEntity` (session_id 기반 스코핑)
  - `todo_id`, `session_id`, `content`, `active_form`, `status`, `order_index`
  - status: PENDING/IN_PROGRESS/COMPLETED
- **Tool 인터페이스**: 전체 교체 방식 (openclaude 동일)
  - input: `{ todos: [{ content, status, activeForm }] }`
  - output: 현재 todo 목록 전체 반환
- **SessionStore 연동**: 세션 메시지와 함께 Redis에 캐시, DB에 영속
- **FE 표시**: 세션 상세 화면에 진행 상태 패널 표시

---

## 3. Task — 백그라운드 작업 관리

### openclaude 구현 분석

- **TaskCreateTool**: 장시간 실행 태스크 생성 (서브에이전트 기반)
  - 태스크는 독립된 실행 컨텍스트를 가짐
  - description, expected_duration, priority 입력
- **TaskGetTool**: 태스크 상세 조회 (상태, 출력, 실행 시간)
- **TaskListTool**: 현재 세션의 태스크 목록 조회
- **TaskUpdateTool**: 태스크 메타데이터 수정
- **TaskOutputTool**: 대용량 태스크 출력 저장 (토큰 절약 — 컨텍스트 윈도우 외부에 저장)
- **TaskStopTool**: 실행 중인 태스크 강제 중지

### Aimbase 구현 방향

- **SubagentRunEntity 확장**: 기존 서브에이전트 실행 엔티티를 Task로 재활용
  - 이미 status/output/token_usage/duration 추적 중
  - 추가 필드: `task_description`, `priority`, `large_output` (JSONB, 컨텍스트 외부 저장)
- **Tool 6종**: SubagentRunner를 래핑하여 Task CRUD 인터페이스 제공
  - TaskCreate → SubagentRunner.runInBackground()
  - TaskStop → SubagentRunner.cancel()
  - TaskOutput → large_output 필드에 별도 저장 (토큰 절약)
- **SubagentRunEntity 재활용의 이점**:
  - 이미 Virtual Thread 기반 비동기 실행 인프라 구축됨
  - WorktreeManager로 격리 실행 가능
  - AgentOrchestrator로 병렬/순차 조율 가능
  - 추가 테이블 없이 기존 스키마 확장만으로 구현

---

## 4. FE 대시보드

### Plan/Todo/Task 통합 대시보드

- **세션 상세 화면 확장**:
  - Plan 패널: 현재 계획의 goals/steps + 완료율 시각화
  - Todo 패널: 체크리스트 형태 (pending/in_progress/completed 색상 구분)
  - Task 패널: 실행 중/완료/실패 태스크 목록 + 실시간 상태 (SSE)
- **워크플로우 스튜디오 연동**:
  - AGENT_CALL 노드에서 Plan Mode 설정 옵션 추가
  - 서브에이전트 실행 시 자동 Plan Mode 활성화 옵션

---

## 선행 의존성

| 의존 대상 | 이유 |
|----------|------|
| ToolRegistry (Sprint 3) | 도구 등록 인프라 |
| EnhancedToolExecutor (CR-029) | 구조화된 도구 인터페이스 |
| ToolFilterContext (CR-006) | Plan Mode readOnly 모드 |
| SubagentRunner (CR-030) | Task 실행 백엔드 |
| SessionStore (Sprint 1) | 세션 스코프 관리 |

---

## 구현 순서 (Phase)

| Phase | 기능 | PRD | 산출물 |
|-------|------|-----|--------|
| 1 | Plan 도메인 + EnterPlanMode + ExitPlanMode | PRD-222, PRD-223 | PlanEntity, EnterPlanModeTool, ExitPlanModeTool |
| 2 | VerifyPlanExecution + Plan FSM | PRD-224 | VerifyPlanExecutionTool, PlanStateMachine |
| 3 | TodoWrite + 세션 체크리스트 | PRD-225 | TodoEntity, TodoWriteTool |
| 4 | Task Create/Get/List | PRD-226 | TaskCreateTool, TaskGetTool, TaskListTool |
| 5 | Task Update/Output/Stop | PRD-227 | TaskUpdateTool, TaskOutputTool, TaskStopTool |
| 6 | FE Plan/Todo/Task 대시보드 | FE-015 | SessionDetail 확장 |
