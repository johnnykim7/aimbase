# 원본 요구사항 — CR-097 SUB_WORKFLOW 비주얼 에디터 노출 (FE 마감)

- **발번일**: 2026-06-05
- **요청자**: sykim
- **연계 CR**: CR-065 (SubWorkflow 자식 run 분리 — 자식 run 트리 가시화는 이쪽에 위임)

## 대화 맥락 (요약)

사용자가 aimbase 워크플로우 실행 이력 화면을 보다가 다음 흐름으로 논의가 전개됨:

1. "워크플로우 실행하면 실행단계가 보이는데, 각 실행단계의 컨텍스트를 보여주는 화면이 필요할 것 같다" → 논의 시작
2. 실측 결과: 실행 이력 탭은 이미 존재(`WorkflowDetail.tsx` history 탭). 단, 각 단계의 **최종 output JSON만** 보이고 프롬프트/응답/tool 본문(컨텍스트)은 BE 적재 자체가 없음 (workflow_run_events 는 메타만, trace_id/subagent_run_id 조인도 워크플로우 경로에선 null 로 끊겨 있음)
3. 사용자가 실행 이력 좌측 목록을 보고 "워크플로우 단위로 나온다, 하나의 명령에 서브 워크플로우가 중첩되는 구조는 아니죠?" 질문
4. 실측: 현재 "1 명령 = run row 1개". `SubWorkflowStepExecutor` 가 부모 run ID 를 그대로 써서 자식 run 이 부모에 흡수됨(트리 미표현). `parent_run_id` 컬럼은 CR-058 로 스키마만 있고 `setParentRunId()` 호출처 0건 → CR-065 미구현 상태 확인
5. 사용자: "워크플로우 밑에 워크플로우를 다시 호출하는 기능은 구현되어 있다는 거죠?" → 일반론 질문 ("남이 만든 워크플로우를 추가하는 게 일반적인 방식인지")
6. 답변: 네, 서브워크플로우/중첩 워크플로우/재사용 컴포넌트는 워크플로우 엔진의 표준 패턴(n8n Sub-workflow, Airflow SubDAG, Temporal Child Workflow, GitHub Reusable workflow, BPMN Call Activity, LangGraph Subgraph 등). aimbase 도 같은 계보. 단 호출 대상은 보수적으로 플랫폼 공용 워크플로우만으로 한정
7. 사용자가 워크플로우 스튜디오 노드 팔레트 스크린샷 제시: "이게 워크플로우 화면인데 어디에 그게 있죠?" → SUB_WORKFLOW 노드가 팔레트에 안 보임
8. 실측: **BE 는 SUB_WORKFLOW StepType + Executor 완성, 그러나 FE 비주얼 에디터에 통째로 미노출**(팔레트/설정/렌더/매핑 4곳 누락)
9. 사용자: "우리도 만들다 만 거에요?" → 답변: 엔진은 완성(만들다 만 게 아님), 제품 기능으로서는 미완성(FE 노출·테스트·자식 run 가시화 미마감). git 확인 결과 2026-04-04 단일 커밋 이후 무수정 + 테스트 0개
10. 사용자: "오케. 마무리 작업 들어가자" → 범위 = "FE 노출 + 자식 run 가시화". 절차 = "CR 발번은 하되 설계 캐스케이드·코딩은 이 세션에서 안 함, 인계 내용만 남김"

## 마감 작업 분해 (실측 기반)

### 본 CR (CR-097) — FE 노출, BE 무변경
FE 4파일 + 타입 1파일:
- `frontend/src/components/workflow/NodePalette.tsx:1` — PALETTE_ITEMS 에 sub_workflow 추가
- `frontend/src/components/workflow/ConfigPanel.tsx:411` — getConfigFields() 에 SUB_WORKFLOW case (workflow_id 드롭다운 + input JSON)
- `frontend/src/components/workflow/WorkflowNode.tsx:4` — TYPE_STYLES 에 sub_workflow / SUB_WORKFLOW
- `frontend/src/utils/flowToWorkflow.ts:5` — TYPE_MAP 에 sub_workflow → SUB_WORKFLOW
- `frontend/src/types/workflow.ts:4` — StepType union 에 sub_workflow 명시

데이터원: 플랫폼 공용 워크플로우 목록 조회 API 확인 필요 (`GET /api/v1/platform/workflows` 추정 — 착수 시 실측). ID 비노출·name 표시 (feedback_id_display 준수).

검증 포인트:
- BE `SubWorkflowStepExecutor` 는 config 에 `workflow_id`(String) + `input`(Map, 변수치환) 을 받음 → FE ConfigPanel 이 정확히 이 두 필드를 생성해야 함
- BE StepType enum 값은 `SUB_WORKFLOW` (대문자 언더스코어). TYPE_MAP 이 FE 소문자 sub_workflow → 대문자 SUB_WORKFLOW 변환
- SubWorkflowStepExecutor 테스트가 0개 → 본 CR 또는 별도로 executor 단위 테스트 추가 검토

### 위임 (CR-065) — 자식 run 트리 가시화
"실행 이력에서 서브워크플로우가 부모 run 에 흡수되지 않고 자식 run 트리로 보이게" 하는 작업은 **이미 발번된 CR-065** 에 정밀 설계되어 있음(5.5MD, Phase 0~5). 본 CR-097 에서 중복 발번하지 않고 CR-065 로 위임.
- CR-065 핵심: WorkflowEngine doExecuteAsync 재진입 구조 분리(순환의존 해소) → SubWorkflowStepExecutor 가 자식 WorkflowRunEntity 생성 + parent_run_id/parent_step_id 설정 → 자식 run 조회/재시도 API → 위젯 트리 UI
- CR-065 착수 전제: 엔진 의존 그래프 재설계(Phase 1) 선행

## 인계 (다른 세션에서 진행 시)

1. 코드 수정·빌드·재기동은 사용자 승인 후에만 (전역 규칙)
2. FE 노드 추가(CR-097)는 가볍고 BE 무변경 → 빠르게 화면 노출 가능
3. 자식 run 가시화는 CR-065(엔진 리팩토링 포함, Medium, 회귀 위험) → 별도 착수
4. 단계 컨텍스트 본문 적재(프롬프트/응답/tool 본문)는 또 다른 갭 — 본 CR 범위 아님(별도 논의 보류)
