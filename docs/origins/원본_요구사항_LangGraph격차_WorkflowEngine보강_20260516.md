# 원본 요구사항 — LangGraph 격차 분석 기반 WorkflowEngine 보강

- **일자**: 2026-05-16
- **발번 CR**: CR-084 (임의 cycle + 동적 N-way 라우팅), CR-085 (State 타입드 채널/reducer + 노드 내부 토큰 스트리밍)
- **요청자**: 사용자
- **승인자**: sykim

## 발단

사용자 질문: "Aimbase에 LangGraph가 도입되어 있나" → 1·2차 답변에서 `agent/reasoning.py` 한 파일만 보고 "미도입" 오판.
사용자 지적 후 `com.platform.workflow` 전수 실측 → LangGraph 라이브러리는 미사용이나 상태 그래프 오케스트레이션 개념을 Java로 자체 구현했음을 확인.

이어 사용자 요청: "LangGraph 기준 우위/부족 비교".

## 비교 결론 (실측 기반)

### Aimbase 우위
- 운영 빌트인이 노드에 내장 (재시도/서킷브레이커/폴백/토큰초과 자동분할)
- 선언적 JSON 정의 + 저장시점 검증 (WorkflowValidator)
- 멀티테넌시 (TenantContext VT 전파)
- 이벤트 스트리밍 운영 통합형 (WorkflowEventPublisher)

### Aimbase 부족 (이번 보강 대상)
1. **임의 cycle 미지원** — EVALUATOR_LOOP 한 패턴으로만 한정. `topologicalSort` 사이클 시 원순서 폴백 (WorkflowEngine.java:616-620). 실행 루프가 단일 for 1-pass (WorkflowEngine.java:280)
2. **동적 N-way 라우팅 미흡** — CONDITION이 true_step/false_step 2갈래 고정 (ConditionStepExecutor.java:42-49)
3. **State가 평면 문자열 치환** — StepContext가 1뎁스 dot 접근만, 실패 시 빈문자열, reducer/누적채널 없음 (StepContext.java:86-125)
4. **노드 내부 토큰 스트리밍 부재** — 워크플로우 이벤트가 step 단위 (WorkflowEngine.java:311-342)

(time-travel 디버깅 부재, 그래프 표현력 코드 vs JSON 트레이드오프는 이번 범위 제외 — 사용자 미선택)

## 사용자 결정

- 4개 격차 전부 채운다
- 진행: CR 발번 + 설계 먼저 (코드 전 규모판단 — design_cascade_first 룰)
- **2개 CR 분할**: CR-084(cycle+N-way, 엔진 스케줄러 재설계 한 덩어리), CR-085(State 채널/reducer + 노드 토큰 스트리밍, StepContext 개편 한 덩어리)
- **호환성 = 옥인 graph_mode 플래그**: 기존 DAG 워크플로우는 1-pass 그대로, `graph_mode:cyclic` 명시 시에만 워크리스트 스케줄러 가동
- **FlowGuard 등록 생략** (사용자 지시 2026-05-16)
