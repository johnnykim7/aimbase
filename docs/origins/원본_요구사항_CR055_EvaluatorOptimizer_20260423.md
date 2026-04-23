# 원본 요구사항 — CR-055: Evaluator-Optimizer 워크플로우 노드

**대화 일자**: 2026-04-23
**관련 CR**: CR-055 (Evaluator-Optimizer 패턴 신규 노드 타입)
**선행 CR**: CR-016 (LLM Judge — 정책 평가용으로만 존재, 워크플로우 미연결)

---

## 발단 — Anthropic 6패턴 커버리지 갭 분석

기획자가 Anthropic "Building Effective Agents" 6가지 패턴 대비 Aimbase 구현 상태를 분석:

| # | 패턴 | Aimbase |
|---|------|---------|
| 1 | Prompt Chaining | 부분 (워크플로우 DAG의 LLM_CALL 순차) |
| 2 | Routing | 완전 (ModelRouter + IntentClassifier) |
| 3 | Parallelization | 완전 (PARALLEL 스텝 + 서브에이전트 병렬) |
| 4 | Orchestrator-Workers | 완전 (AgentOrchestrator → SubagentRunner) |
| 5 | **Evaluator-Optimizer** | **없음** |
| 6 | Autonomous Agent | 완전 (ToolCallHandler.executeLoop 최대 30회 + Hook) |

**갭**: 생성 → 평가 → 재생성 피드백 루프가 워크플로우 노드로 표현 불가.
- CR-016 LLM Judge는 정책 평가용(DENY/REQUIRE_APPROVAL)이며 출력 평가 → 재생성 루프와 무관
- `PlanService.verify()`는 상태 전이만 하고 LLM 기반 재시도 없음
- 현재 `WorkflowStep.StepType` 8종에 `EVALUATOR/LOOP` 계열 부재

---

## 설계 방향 — 2가지 옵션 검토

### 옵션 A — 최소 스펙: EVALUATOR_LOOP 단일 노드

- 새 StepType 1개만 추가
- 노드 **내부**에 generator + evaluator + max_iterations + pass_criteria 캡슐화
- DAG 외부에서는 여전히 한 노드 — 위상 정렬 영향 없음
- React Flow UI: 노드 타입 1개만 신규

### 옵션 B — 조합형: EVALUATOR 노드 + LOOP_BACK 엣지

- StepType 2개 + 신규 엣지 타입
- 임의 노드로 되돌아가는 일반화된 제어 흐름
- DAG → 일반 그래프로 전환 필요 (사이클 허용, 방문 카운터, max_hops)
- 기존 validate/비순환성 로직 전량 수정

---

## 결정 — 옵션 A 채택

> **사용자**: 예 A로 가주시고요

**채택 사유**
1. Anthropic 원문의 Evaluator-Optimizer는 "닫힌 2-노드 루프"가 핵심 — A가 정확히 매칭
2. 기존 WorkflowEngine의 Kahn 위상 정렬(BIZ-009) 전제 보존
3. WorkflowStudio UI 변경 최소 (노드 타입 1개 추가)
4. 실사용에서 B의 유연성이 필요해지면 CR 신규로 확장 (YAGNI)
5. 메모리 `feedback_design_cascade_first` — 규모 판단 먼저, 과설계 지양

**향후 확장 여지**
- "2-노드 루프로 표현 안 되는 케이스"가 3건 이상 쌓이면 B를 신규 CR로 열어 확장
- `SUB_WORKFLOW` + `EVALUATOR_LOOP` 조합으로 다단계 루프는 당장 커버 가능

---

## 6패턴 사용자 진입점 정리 (기획자 이해용)

| 패턴 | Aimbase 진입점 | 사용자 액션 |
|------|---------------|-------------|
| 1. Chaining | `WorkflowStudio` (`/workflows/new`) | LLM 노드 직선 연결 |
| 2. Routing | `POST /api/v1/chat/completions` | `model: "auto"` 파라미터 |
| 3. Parallelization | WorkflowStudio PARALLEL / Chat 자동 | 노드 추가 또는 LLM 자동 |
| 4. Orchestrator-Workers | Chat (자동) | Task 도구 허용 |
| 5. **Evaluator-Optimizer** | **(신규)** WorkflowStudio | **EVALUATOR_LOOP 노드 추가 (CR-055)** |
| 6. Autonomous | Chat (기본) | Plan Mode 토글, Hook 설정 |

**관찰**: "워크플로우로 명시적으로 그리는 패턴(1·3·5)" vs "Chat에서 암묵적으로 발생하는 패턴(2·4·6)" 두 갈래.
CR-055 완료 시 "그리는 쪽"의 표현력이 완성되어, 품질 루프가 필요한 워크플로우를 시각적으로 설계 가능.

---

## 핵심 설계 요약 (CR-055 초안)

| 항목 | 결정 |
|------|------|
| 신규 StepType | `EVALUATOR_LOOP` (1종 추가, 총 9종) |
| 노드 내부 구성 | generator(LLM_CALL 참조) + evaluator(LLM_CALL 참조) |
| 종료 조건 | `max_iterations` (기본 3, 상한 10) + `pass_criteria` |
| pass_criteria 타입 | `SCORE_THRESHOLD` (Judge 점수) / `JSONPATH_MATCH` / `LLM_JUDGE` (별도 프롬프트) |
| 반복 컨텍스트 | 직전 출력 + 평가 피드백을 generator에 주입 (`{{loop.previous_output}}`, `{{loop.feedback}}`) |
| 중단 정책 | max_iterations 도달 시 마지막 출력 반환 + `loop_exhausted: true` 플래그 |
| 감사 | 매 iteration별 generator/evaluator 입출력을 workflow_run_steps에 개별 row |
| DAG 외부 영향 | 없음 (노드 내부 캡슐화, 위상 정렬 불변) |
| FE 영향 | WorkflowStudio 노드 팔레트에 1종 추가 + 속성 패널 (generator/evaluator/criteria 편집) |
| 프롬프트 | 기본 evaluator 프롬프트 템플릿 제공 (한국어/영문) — CR-036 prompt_templates 경로 |

**비대칭성 원칙** (Anthropic 원문 강조):
- Evaluator는 Generator와 **다른 프롬프트/관점**이어야 효과. 같은 LLM이 자기 출력 평가 시 개선폭 작음
- UI에서 "문학 평론가 역할", "엄격한 시니어 리뷰어" 같은 역할 지정 프롬프트 기본 제공

---

## 적용 시나리오 (기획자 검증용)

1. **문학 번역 개선**: 초벌 번역 → 원문 뉘앙스 평가 → 재번역 (3~5회)
2. **마케팅 카피**: 카피 생성 → 타겟 페르소나 관점 평가 → 개선
3. **코드 리뷰 대응**: 코드 작성 → 린트/설계 기준 평가 → 수정
4. **검색 쿼리 개선**: 초기 쿼리 → 결과 충분성 평가 → 쿼리 재작성
5. **문서 요약 품질**: 요약 생성 → 핵심 보존도 평가 → 재요약

---

## CR 번호 발번

`docs/CR_변경_이력.md` 기준 최신 CR-054 다음으로 발번 → **CR-055**.

---

## 메모리/규칙 준수

- **feedback_cr_origins**: CR 등록 시 docs/origins/에 대화 원본 저장 → 본 파일
- **feedback_design_cascade_first**: 기능 추가 전 규모 판단 + CR 확인 → 옵션 A/B 비교 후 A 채택
- **feedback_proactive_ux_suggestion**: BE 노드 타입 + FE WorkflowStudio UI 동시 범위
- BIZ-009 (워크플로우 DAG Kahn 위상 정렬) **보존** — 루프는 노드 내부 캡슐화

---

## 후속 작업

1. `docs/CR_변경_이력.md`에 CR-055 등재
2. T3 상세 설계서 작성 (`docs/T3-X_CR-055_EvaluatorOptimizer.md`)
   - DB 스키마: `workflow_steps.config` JSONB 구조 확정
   - API: 기존 `/api/v1/workflows/**` 재사용 (신규 엔드포인트 없음)
   - FE: WorkflowStudio 노드 팔레트 + 속성 패널 스펙
   - 프롬프트: 기본 evaluator 템플릿 (prompt_templates seed)
3. 사용자 승인 후 Sprint 52 구현 착수
