# 원본 요구사항 — CR-065 SubWorkflow 자식 run 분리 생성

**수집일**: 2026-04-24
**출처**: `docs/T3-9_CR-058_ChatWidget_설계서.md § 9-1` (line 779~783) — CR-058 Sprint 52 구현 중 발견된 델타
**발번 결정**: 2026-04-24 마스터 세션에서 "B안 (CR-065/066)" 으로 확정. 인계서 `docs/origins/CR065_066_발번_인계서_20260424.md` 참조

---

## 배경

CR-058 Chat Widget SDK (Sprint 52+53) 구현 중 `SubWorkflowStepExecutor` 의 child run 분리 생성 원안을 스킵함.

- **원안**: 서브워크플로우 실행 시 `WorkflowRunEntity` 를 자식용으로 별도 생성하고 `parent_run_id`·`parent_step_id` 를 설정해 실존 트리로 저장
- **실제 구현**: 기존 인라인 실행을 유지하고, `workflow.step` SSE 이벤트 payload 의 `sub_workflow_id` 로 자식 표시를 대체. DB 스키마(`parent_run_id` 컬럼) 는 정상 적용되어 후속 CR 에서 child run 생성으로 승격 가능한 여지 유지
- **스킵 사유**: 자식 run 을 별도 레코드로 만들려면 `SubWorkflowStepExecutor` 가 `WorkflowEngine` 의 `doExecuteAsync` 경로를 재진입해야 하는데, 현재 구조상 executor 레이어에서 엔진 재호출은 순환 의존을 유발. 별도 리팩토링이 필요

## 영향

- **기능 가능**: 위젯 트리 UI 는 SSE 이벤트 기반으로 자식을 표시 가능
- **기능 불가**:
  - 자식 런 전용 `stepResults` 기록
  - 자식 런 개별 재시도
  - 자식 run 단위 개별 조회 API

## 요구사항 (재확인)

### 원안 복원 범위
- 자식 `WorkflowRunEntity` 별도 레코드 생성
- `parent_run_id` + `parent_step_id` 실존 트리 저장
- 자식 run 개별 stepResults 기록

### 해결의 핵심 난점
- `SubWorkflowStepExecutor` 가 `WorkflowEngine.doExecuteAsync()` 재진입 필요
- 현재 executor → engine 재호출은 순환 의존
- → `WorkflowEngineInternal` 등 의존 그래프 재설계 필요

### 신설 API
- `GET /api/v1/workflows/runs/{parentId}/children` — 자식 run 목록
- `POST /api/v1/workflows/runs/{childId}/retry` — 자식 단위 재시도

### 호환성 제약
- 기존 `workflow.step` SSE 이벤트 payload 의 `sub_workflow_id` 필드 유지 (위젯 이미 소비 중)
- 신규 `sub_workflow_run_id` 추가 시 기존 필드 제거 금지

### 위젯 FE 증분 (CR-058 SDK 업데이트)
- 트리 UI 에 "자식 run 재시도" 버튼
- 자식 run 상태 개별 표시

## 영향 모듈 (개발 착수 시 점검)

- `backend/platform-core/src/main/java/com/platform/workflow/step/SubWorkflowStepExecutor.java` — 핵심 수정
- `backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java` — doExecuteAsync 진입점 정리
- `backend/platform-core/src/main/java/com/platform/domain/WorkflowRunEntity.java` — `parent_run_id` 이미 존재, 활용만
- `backend/platform-core/src/main/java/com/platform/api/WorkflowController.java` — 자식 run 조회/재시도 API 신설
- 위젯 FE — `packages/chat-widget-embed/src/widget.ts` 또는 트리 컴포넌트

## 착수 시점 판단 기준

- 위젯 트리 UI 에서 **"자식 run 재시도"·"개별 조회" 요구가 실사용에서 제기될 때**
- 현재는 화면 표시 자체는 되므로 **긴급하지 않음**
- CR-058 배포 후 사용자 피드백 수집 기간 경과 후 판단

## 완료 기준

- [ ] 자식 run 이 `workflow_runs` 별도 레코드로 저장됨
- [ ] `parent_run_id` 기반 트리 조회 API 동작
- [ ] 자식 run 개별 재시도 API 동작
- [ ] 기존 인라인 SSE 이벤트 포맷 호환성 유지 (위젯 기존 코드 무수정 동작)
- [ ] 회귀 테스트 PASS (단위/통합)

---

## 참고 — 원문 인용

`docs/T3-9_CR-058_ChatWidget_설계서.md § 9-1` 원문:

> ### 9-1. S3-2 `SubWorkflowStepExecutor` child run 분리 생성 — **스킵**
> - **원안**: 서브워크플로우 실행 시 `WorkflowRunEntity` 를 자식용으로 별도 생성하고 `parent_run_id`·`parent_step_id` 를 설정해 실존 트리로 저장.
> - **실제 구현**: 기존 인라인 실행을 유지하고, `workflow.step` SSE 이벤트 payload 의 `sub_workflow_id` 로 자식 표시를 대체. DB 스키마(`parent_run_id` 컬럼) 는 정상 적용되어 후속 CR 에서 child run 생성으로 승격 가능한 여지 유지.
> - **사유**: 자식 run 을 별도 레코드로 만들려면 SubWorkflowStepExecutor 가 WorkflowEngine 의 doExecuteAsync 경로를 재진입해야 하는데 현재 구조상 executor 레이어에서 엔진 재호출은 순환 의존을 유발. 별도 리팩토링이 필요.
> - **영향**: 위젯 트리 UI 는 이벤트 기반으로 자식을 표시할 수 있으나, "자식 런 전용 stepResults 기록" 이나 "자식 런 개별 재시도" 는 불가. 필요해지면 후속 CR.
