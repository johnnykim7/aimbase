# T3-7 | CR-055 Evaluator-Optimizer 워크플로우 노드 상세 설계서

**문서 번호**: T3-7
**관련 CR**: CR-055
**작성일**: 2026-04-23
**상태**: 작성 완료 — 구현 착수 대기
**원본 요구사항**: [docs/origins/원본_요구사항_CR055_EvaluatorOptimizer_20260423.md](origins/원본_요구사항_CR055_EvaluatorOptimizer_20260423.md)

---

## 1. 목적과 범위

### 1.1 목적
Anthropic "Building Effective Agents" 6패턴 중 유일한 미구현 패턴인 **Evaluator-Optimizer**를 Aimbase 워크플로우 스튜디오에서 시각적으로 설계·실행할 수 있도록 한다. 생성 → 평가 → 재생성 피드백 루프를 하나의 워크플로우 노드로 캡슐화한다.

### 1.2 범위 — 포함
- `WorkflowStep.StepType` enum에 `EVALUATOR_LOOP` 1종 추가
- 노드 내부 실행기 `EvaluatorLoopStepExecutor` 신규 구현
- `StepContext`의 `{{loop.*}}` 변수 namespace 추가 (iteration 내부 컨텍스트)
- `workflow_runs.step_results` JSONB 내 `iterations[]` 필드 규약 확정 (테이블 스키마 변경 없음)
- WorkflowStudio 노드 팔레트에 신규 노드 1종 + 속성 패널
- 기본 evaluator 프롬프트 템플릿 3종 seed (`prompt_templates` 테이블, CR-036 경로)
- 단위 테스트 (executor 10+ 케이스) + 통합 테스트 (엔진 3 케이스)

### 1.3 범위 — 제외
- `LOOP_BACK` 엣지 / 임의 노드로 되돌아가는 일반화된 제어 흐름 → 옵션 B, 별도 CR
- DAG → 일반 그래프 전환, 사이클 감지, max_hops 런타임 로직
- Chat API에서의 루프 사용 (현재 `ToolCallHandler.executeLoop`가 담당, 본 CR은 워크플로우 한정)
- 다단계 루프(3-노드 이상) 전용 노드 타입 → `SUB_WORKFLOW` + `EVALUATOR_LOOP` 조합으로 커버

### 1.4 선행 의존성
- CR-032 멀티 프로바이더 LLM 어댑터 (generator/evaluator 각각 다른 connection_id 지정 가능)
- CR-036 `prompt_templates` 테이블 (기본 evaluator 프롬프트 seed 경로)
- CR-016 LLM Judge 인프라 — **재사용하지 않음** (Judge는 정책용). evaluator는 `LlmCallStepExecutor`와 동일 계열의 일반 LLM 호출로 구현하여 복잡도 최소화.

---

## 2. 데이터 모델 변경

### 2.1 Enum 확장 — WorkflowStepType
[backend/platform-core/src/main/java/com/platform/workflow/model/WorkflowStep.java:17-19](../backend/platform-core/src/main/java/com/platform/workflow/model/WorkflowStep.java#L17-L19)

```java
public enum StepType {
    LLM_CALL, TOOL_CALL, ACTION, CONDITION, PARALLEL,
    HUMAN_INPUT, SUB_WORKFLOW, AGENT_CALL,
    EVALUATOR_LOOP  // CR-055 신규
}
```

T3-1 업데이트: `WorkflowStepType` 표에 한 행 추가.

| 값 | 설명 |
|----|------|
| EVALUATOR_LOOP | 생성-평가-재생성 루프 (한 노드 내부에서 반복) [v7.10, CR-055] |

### 2.2 테이블 스키마 변경 — 없음
`workflow_runs.step_results` JSONB를 그대로 사용. iteration 이력은 해당 스텝의 결과 Map 안에 `iterations[]` 배열로 누적 저장한다. 별도 테이블/컬럼 추가 없음 → **마이그레이션 SQL 없음**.

### 2.3 `step_results` 내부 EVALUATOR_LOOP 결과 스키마

`workflow_runs.step_results[<stepId>]`에 저장되는 구조:

```json
{
  "output": "<최종 generator 출력 (raw text 또는 structured JSON)>",
  "structured_data": { "...": "..." },
  "iterations": [
    {
      "index": 0,
      "generator_output": "...",
      "evaluator_verdict": {
        "score": 7.2,
        "passed": false,
        "feedback": "원문의 은유가 평이하게 직역됨. 문장 리듬 부족."
      },
      "generator_ms": 3240,
      "evaluator_ms": 1180,
      "input_tokens": 524,
      "output_tokens": 312
    },
    {
      "index": 1,
      "generator_output": "...",
      "evaluator_verdict": { "score": 8.9, "passed": true, "feedback": "..." },
      "generator_ms": 3510,
      "evaluator_ms": 1050,
      "input_tokens": 612,
      "output_tokens": 340
    }
  ],
  "final_iteration": 1,
  "loop_exhausted": false,
  "pass_criteria_type": "SCORE_THRESHOLD",
  "total_duration_ms": 9080,
  "total_input_tokens": 1136,
  "total_output_tokens": 652
}
```

**설계 근거**
- `output` 필드는 최종 generator 출력 — 후속 스텝에서 `{{<stepId>.output}}`으로 동일 접근 규약 유지
- `iterations[]`는 감사/디버깅용 전체 이력 — UI에서 "반복 보기" 탐색에 사용
- `loop_exhausted: true`는 max_iterations 소진으로 종료된 경우 (품질 미달 상태 반환)
- 토큰 합산은 비용 집계 용이하게 상위에 집계

---

## 3. StepConfig JSONB 스펙

워크플로우 정의 JSON의 `steps[]` 배열 내 EVALUATOR_LOOP 스텝 형식:

```json
{
  "id": "translate-and-refine",
  "name": "문학 번역 개선 루프",
  "type": "EVALUATOR_LOOP",
  "dependsOn": ["extract-source-text"],
  "config": {
    "max_iterations": 3,

    "generator": {
      "model": "auto",
      "connection_id": null,
      "system": "당신은 한국어-영어 문학 번역가입니다. 원문의 은유와 리듬을 최대한 살려 번역하세요.",
      "prompt": "원문:\n{{extract-source-text.output}}\n\n{{#if loop.iteration > 0}}직전 번역:\n{{loop.previous_output}}\n\n평론가 피드백:\n{{loop.feedback}}\n\n위 피드백을 반영해 다시 번역해주세요.{{/if}}",
      "max_tokens": 4096
    },

    "evaluator": {
      "model": "auto",
      "connection_id": null,
      "prompt_template_key": "evaluator_literary_critic",
      "max_tokens": 1024,
      "response_format": {
        "type": "json_schema",
        "schema": {
          "type": "object",
          "required": ["score", "passed", "feedback"],
          "properties": {
            "score":   { "type": "number", "minimum": 0, "maximum": 10 },
            "passed":  { "type": "boolean" },
            "feedback":{ "type": "string", "maxLength": 2000 }
          }
        }
      }
    },

    "pass_criteria": {
      "type": "SCORE_THRESHOLD",
      "field": "score",
      "threshold": 8.5,
      "operator": "GTE"
    }
  },
  "timeoutMs": 120000
}
```

### 3.1 필드 정의

| 필드 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| `max_iterations` | int | ✅ | 3 | 최대 반복. **상한 10** (BIZ-110, [§ 5.1](#51-새로-정의하는-비즈니스-규칙) 참조) |
| `generator.model` | string | ✅ | "auto" | `LLM_CALL`과 동일 규약 |
| `generator.connection_id` | string | ❌ | null | Connection ID (없으면 ModelRouter 폴백) |
| `generator.system` | string | ❌ | null | 시스템 프롬프트 (변수 치환 지원) |
| `generator.prompt` | string | ✅ | - | 유저 프롬프트 (변수 치환 지원, `{{loop.*}}` 포함) |
| `generator.max_tokens` | int | ❌ | 4096 | LLM_CALL과 동일 |
| `evaluator.model` | string | ✅ | "auto" | generator와 **다른 모델 권장** (비대칭성 원칙) |
| `evaluator.connection_id` | string | ❌ | null | - |
| `evaluator.prompt_template_key` | string | ❌ | - | prompt_templates 테이블 참조 (아래 3종 seed 중 택1) |
| `evaluator.prompt` | string | ❌ | - | `prompt_template_key` 대안, 인라인 프롬프트. 둘 중 하나 필수 |
| `evaluator.response_format` | object | ✅ | - | **JSON 강제 필수** — pass_criteria가 구조화 필드를 읽기 때문 |
| `pass_criteria.type` | enum | ✅ | - | `SCORE_THRESHOLD` / `JSONPATH_MATCH` / `LLM_JUDGE` |
| `pass_criteria.field` | string | ✅* | - | (SCORE_THRESHOLD) evaluator 출력 JSON 필드 경로 |
| `pass_criteria.threshold` | number | ✅* | - | (SCORE_THRESHOLD) 통과 임계값 |
| `pass_criteria.operator` | enum | ❌ | `GTE` | `GTE` / `GT` / `LTE` / `LT` / `EQ` |

### 3.2 `pass_criteria.type` 3종 상세

**SCORE_THRESHOLD** (기본, 권장)
- evaluator 응답 JSON의 `field` 경로 값을 숫자로 해석
- `operator`에 따라 `threshold`와 비교 → 통과 여부 결정
- 예: `{type, field: "score", threshold: 8.5, operator: "GTE"}` → evaluator가 score≥8.5 반환 시 루프 종료

**JSONPATH_MATCH**
- evaluator 응답에 대해 JSONPath 평가 → 결과가 true/비어있지 않음이면 통과
- 예: `{type, jsonpath: "$.passed", expected: true}` → evaluator가 `{"passed": true, ...}` 반환 시 종료

**LLM_JUDGE**
- evaluator의 응답 `passed` 필드를 그대로 사용 (가장 단순, 평가 로직을 evaluator 프롬프트에 위임)
- `pass_criteria: {type: "LLM_JUDGE"}` 한 줄로 설정 끝 — MVP에서 UI 기본값으로 제안
- `response_format.schema.properties.passed: boolean` 필수

**결정**: 초기 출시는 **SCORE_THRESHOLD를 UI 기본값**으로. 이유는 임계값이라는 단일 다이얼로 직관적 튜닝 가능 + 감사 로그에 숫자 점수가 누적되어 워크플로우 품질 회고가 쉬움.

---

## 4. 실행기 설계 — EvaluatorLoopStepExecutor

### 4.1 클래스 위치 및 인터페이스

[backend/platform-core/src/main/java/com/platform/workflow/step/EvaluatorLoopStepExecutor.java](../backend/platform-core/src/main/java/com/platform/workflow/step/EvaluatorLoopStepExecutor.java) (신규)

기존 `StepExecutor` 인터페이스 준수:

```java
@Component
public class EvaluatorLoopStepExecutor implements StepExecutor {
    @Override public WorkflowStep.StepType supports() { return StepType.EVALUATOR_LOOP; }
    @Override public Map<String, Object> execute(WorkflowStep step, StepContext context);
}
```

### 4.2 의존성

- `LlmCallStepExecutor` — **재사용**. generator/evaluator 모두 내부적으로 LLM 호출 1회이므로, 가상의 `WorkflowStep`을 조립해서 `LlmCallStepExecutor.execute()`를 그대로 호출. 코드 중복 및 LLM 호출 규약 분기 방지.
- `PromptTemplateService` — `evaluator.prompt_template_key` 지정 시 템플릿 본문 로드
- `ObjectMapper` — evaluator 응답 JSON 파싱
- (선택) `JsonPath` 라이브러리 — `JSONPATH_MATCH` 지원. 이미 `build.gradle.kts`에 포함된 Jayway JsonPath 활용

### 4.3 실행 흐름 (의사 코드)

```
execute(step, ctx):
    cfg = step.config()
    maxIter = min(cfg.max_iterations ?: 3, 10)  // BIZ-110 상한

    iterations = []
    prevOutput = null
    prevFeedback = null
    finalVerdict = null

    for i in 0 until maxIter:
        // 1) Generator 실행
        loopVars = {iteration: i, previous_output: prevOutput ?: "", feedback: prevFeedback ?: ""}
        genCtx = ctx.withLoopVars(loopVars)
        genStep = buildVirtualLlmStep("<stepId>.gen." + i, cfg.generator)
        genResult = llmExecutor.execute(genStep, genCtx)
        genOutput = genResult.output

        // 2) Evaluator 실행 (generator 출력을 evaluator 입력으로 주입)
        evalVars = {iteration: i, generator_output: genOutput, previous_feedback: prevFeedback ?: ""}
        evalCtx = ctx.withLoopVars(evalVars)
        evalStep = buildVirtualLlmStep("<stepId>.eval." + i, cfg.evaluator)
        evalResult = llmExecutor.execute(evalStep, evalCtx)
        verdict = parseJson(evalResult.output)  // {score, passed, feedback, ...}

        // 3) 기록
        iterations.add({index: i, generator_output: genOutput, evaluator_verdict: verdict, ...})

        // 4) pass_criteria 평가
        if evaluatePassCriteria(cfg.pass_criteria, verdict):
            return buildResult(output=genOutput, iterations, final_iteration=i, loop_exhausted=false)

        prevOutput = genOutput
        prevFeedback = verdict.feedback

    // 루프 소진
    lastGen = iterations.last().generator_output
    return buildResult(output=lastGen, iterations, final_iteration=maxIter-1, loop_exhausted=true)
```

### 4.4 실패 처리

| 실패 지점 | 처리 |
|----------|------|
| Generator LLM 호출 실패 (네트워크/토큰) | 해당 iteration 실패 기록 후 다음 iteration 진행 (`max_iterations` 소진 시 종료). 단, 연속 3회 실패 시 전체 스텝 FAIL 반환 |
| Evaluator LLM 호출 실패 | 동일 (연속 3회 실패 시 FAIL) |
| Evaluator JSON 파싱 실패 | 해당 iteration을 `verdict=null`로 기록, pass 실패로 간주하고 다음 iteration 진행 |
| pass_criteria 평가 중 스키마 불일치 (필드 없음 등) | 스텝 FAIL 반환 (설정 오류는 감춰선 안 됨) |
| `timeoutMs` 초과 | WorkflowEngine의 기존 timeout 로직에 따라 스텝 FAIL |

### 4.5 로깅 및 감사

- iteration 시작/종료마다 `log.info("EVALUATOR_LOOP [{}] iter {}/{} score={} passed={}", stepId, i, maxIter-1, score, passed)`
- `workflow_runs.step_results`에 전체 `iterations[]` 누적 → 기존 감사 로깅 인프라 자동 수혜
- LLM 호출별 `platformMetrics.recordLlmCall(...)` 자동 호출 (LlmCallStepExecutor 재사용 효과)

---

## 5. 비즈니스 규칙 변경

### 5.1 새로 정의하는 비즈니스 규칙

**BIZ-110 (신규)**: EVALUATOR_LOOP 노드의 `max_iterations`는 **런타임 상한 10**으로 하드 제한한다.
- 이유: 폭주 방지 + 비용 예측 가능성. 사용자가 50 같은 값을 넣어도 서버에서 10으로 clamp
- 적용: `EvaluatorLoopStepExecutor.execute()` 진입 시 `Math.min(userValue, 10)`
- UI: 슬라이더 max=10으로 표기

**BIZ-009 (기존 보존)**: 워크플로우 DAG는 Kahn 위상 정렬 가능해야 한다 (비순환성).
- EVALUATOR_LOOP는 노드 **내부**에서 루프 완결 → DAG 외부 불변식 유지. 본 CR은 BIZ-009에 영향 없음.

### 5.2 기존 규칙 영향 점검

| 규칙 | 영향 여부 | 비고 |
|------|----------|------|
| BIZ-001 도구 호출 루프 최대 5회 | 무영향 | Chat API 한정 |
| BIZ-009 워크플로우 DAG 위상 정렬 | 보존 | 노드 내부 캡슐화 |
| BIZ-020 감사 로깅 필수 | 준수 | iteration별 기록 |
| BIZ-028 (CR-028) LLM_CALL 토큰 초과 자동처리 | 자동 상속 | LlmCallStepExecutor 재사용 효과 |

---

## 6. API 설계

**신규 엔드포인트 없음**. 기존 `/api/v1/workflows` 엔드포인트가 JSONB로 steps 스펙을 받는 구조이므로, 노드 타입 `EVALUATOR_LOOP`는 스펙 수준 확장만으로 전달 가능.

### 6.1 검증 추가 (`WorkflowService.validate()` 수정)

워크플로우 저장 시 유효성 검증에 EVALUATOR_LOOP 규칙 추가:

- `config.max_iterations`: 1 ≤ N ≤ 10 (초과 시 `VALIDATION_ERROR` 반환)
- `config.generator.prompt`: 비어있지 않음
- `config.evaluator.prompt_template_key` XOR `config.evaluator.prompt`: 정확히 하나
- `config.evaluator.response_format.type`: `json_schema` 필수
- `config.pass_criteria.type`: 3종 enum 중 하나
- `SCORE_THRESHOLD`일 때: `field` + `threshold` 필수

[backend/platform-core/src/main/java/com/platform/service/WorkflowService.java] (기존 파일 수정)

### 6.2 OpenAPI / T3-2 문서 반영

T3-2 `WorkflowStepType` 테이블과 "워크플로우 생성" 요청 예시에 EVALUATOR_LOOP 케이스 추가 (1 블록).

---

## 7. 프론트엔드 설계

### 7.1 파일 구조

| 파일 | 변경 유형 | 내용 |
|------|----------|------|
| [frontend/src/types/workflow.ts] | 수정 | `StepType` 유니온에 `'EVALUATOR_LOOP'` 추가 + `EvaluatorLoopConfig` 인터페이스 |
| [frontend/src/components/workflow/nodes/EvaluatorLoopNode.tsx] | **신규** | React Flow 커스텀 노드 컴포넌트 |
| [frontend/src/components/workflow/NodePalette.tsx] | 수정 | 팔레트에 "평가-최적화 루프" 항목 추가 |
| [frontend/src/components/workflow/StepConfigPanel.tsx] | 수정 | EVALUATOR_LOOP 전용 속성 편집 섹션 분기 |
| [frontend/src/components/workflow/panels/EvaluatorLoopPanel.tsx] | **신규** | 전용 속성 패널 (generator/evaluator/pass_criteria 3 탭) |
| [frontend/src/pages/WorkflowRunDetail.tsx] | 수정 | iteration 이력 확장 뷰 ("반복 N회 펼쳐보기") |

### 7.2 노드 시각화

- **아이콘**: Lucide `RefreshCw` (화살표 원) — 루프 의미 즉시 전달
- **기본 색상**: 보라 계열 (`bg-purple-500/10 border-purple-500`) — 기존 LLM_CALL(파랑)/PARALLEL(초록)과 구분
- **노드 본문 표시**:
  - 상단: "평가-최적화 루프"
  - 중앙: generator 모델 / evaluator 모델 2줄
  - 하단: `max_iter: 3 · score≥8.5` 같은 요약 배지

### 7.3 속성 패널 UX (EvaluatorLoopPanel)

3개 탭 구조:

**탭 1: Generator**
- 모델 선택 (Select: auto/haiku/sonnet/opus/connection)
- 시스템 프롬프트 (Textarea, 변수 치환 힌트 표시)
- 유저 프롬프트 (Textarea, `{{loop.previous_output}}` `{{loop.feedback}}` 자동완성 버튼)
- max_tokens (슬라이더 1K-16K)

**탭 2: Evaluator**
- 모델 선택 (기본값 Generator와 다른 모델 권장 배너 표시)
- 프롬프트 모드 토글: **[템플릿 사용 | 직접 작성]**
  - 템플릿: 드롭다운에 3종 seed (`evaluator_literary_critic`, `evaluator_code_reviewer`, `evaluator_persona_copy`) + 미리보기 패널
  - 직접 작성: Textarea + response_format JSON 에디터
- "비대칭 원칙" 툴팁: "Generator와 같은 모델을 쓰면 자기 출력을 평가하게 되어 개선폭이 작아집니다"

**탭 3: 종료 조건**
- pass_criteria.type 라디오 (SCORE_THRESHOLD / JSONPATH_MATCH / LLM_JUDGE)
- SCORE_THRESHOLD 선택 시:
  - field 입력 (기본값 "score")
  - threshold 숫자 입력
  - operator 선택 (GTE 기본)
- max_iterations 슬라이더 (1-10, 기본 3)

### 7.4 실행 결과 뷰 (WorkflowRunDetail)

기존 스텝 결과 카드에 EVALUATOR_LOOP 한정 확장 뷰:
- "총 3회 중 2회차에 통과 (8.9점)" 요약 배지
- "반복 이력 펼쳐보기" 클릭 시 각 iteration의 generator 출력 + evaluator verdict을 아코디언으로 표시
- 각 iteration의 토큰/소요시간도 표기 (비용 회고용)

---

## 8. 프롬프트 템플릿 Seed

CR-036 `prompt_templates` 테이블에 3종 추가. Flyway 마이그레이션 1건:
`backend/platform-core/src/main/resources/db/migration/tenant/V{next}__cr055_evaluator_prompts.sql`

### 8.1 `evaluator_literary_critic` (문학/번역 비평)

```
당신은 까다로운 문학 평론가입니다.
아래 생성물을 원문의 의도·은유·리듬 보존 관점에서 평가하세요.

원문: {{loop.source}}
생성물: {{loop.generator_output}}
{{#if loop.previous_feedback}}직전 피드백: {{loop.previous_feedback}}{{/if}}

JSON으로만 응답:
{
  "score":   0~10 점수 (10이 완벽),
  "passed":  score >= 8.5 여부,
  "feedback":"구체적인 개선 방향 (2-3문장)"
}
```

### 8.2 `evaluator_code_reviewer` (코드 리뷰)

```
당신은 엄격한 시니어 엔지니어입니다. 아래 코드를 다음 기준으로 평가하세요:
- 정확성 (요구사항 충족)
- 가독성 (명명, 구조)
- 에러 처리
- 테스트 가능성

요구사항: {{loop.requirement}}
제출된 코드:
```
{{loop.generator_output}}
```

JSON으로만 응답:
{"score": 0~10, "passed": score >= 8, "feedback": "..."}
```

### 8.3 `evaluator_persona_copy` (마케팅 카피 페르소나 평가)

```
당신은 다음 페르소나입니다: {{loop.persona}}
아래 마케팅 카피가 당신에게 얼마나 매력적인지 평가하세요.
매력도(관심 유발), 신뢰도(과장 없음), 명확도(가치 전달) 3가지 기준.

카피: {{loop.generator_output}}

JSON으로만 응답:
{"score": 0~10, "passed": score >= 7.5, "feedback": "어떤 부분이 약한지"}
```

**언어 정책**: 각 템플릿은 영문 버전도 함께 seed (CR-036의 `locale` 컬럼). 기본 `locale=ko_KR`, 영문은 `en_US`.

---

## 9. StepContext 확장

### 9.1 `{{loop.*}}` 네임스페이스 추가

[backend/platform-core/src/main/java/com/platform/workflow/StepContext.java](../backend/platform-core/src/main/java/com/platform/workflow/StepContext.java) 수정:

- `resolveRef()`의 namespace 분기에 `"loop"` 추가
- EvaluatorLoopStepExecutor가 iteration 진입 전 `ctx.withLoopVars({iteration, previous_output, feedback, ...})`로 컨텍스트를 복사해 전달
- 루프 외부에서 `{{loop.*}}` 참조 시 빈 문자열 (기존 누락 변수 처리 규약과 동일)

### 9.2 새 메서드

```java
public StepContext withLoopVars(Map<String, Object> loopVars) {
    // inputData를 건드리지 않고 별도 loopData 필드로 주입
    return new StepContext(workflowRunId, workflowId, sessionId, inputData, stepResults, loopVars);
}
```

record 필드에 `Map<String, Object> loopData` 1개 추가 (nullable). 루프 외부 기존 사용처는 `null`로 호출되므로 영향 없음.

---

## 10. 테스트 명세

### 10.1 단위 테스트 — `EvaluatorLoopStepExecutorTest` (신규 10 케이스)

| # | 케이스 | 검증 포인트 |
|---|--------|------------|
| 1 | SCORE_THRESHOLD 1회차 통과 | iterations.length=1, loop_exhausted=false |
| 2 | SCORE_THRESHOLD 3회 소진 후 종료 | loop_exhausted=true, output=마지막 gen |
| 3 | SCORE_THRESHOLD 2회차 통과 | previous_output/feedback이 2회차 generator에 주입 |
| 4 | JSONPATH_MATCH 통과 조건 | `$.passed=true` 만족 시 종료 |
| 5 | LLM_JUDGE `passed` 필드 기반 | evaluator 응답의 passed 그대로 사용 |
| 6 | Generator 호출 1회 실패 후 다음 iter 성공 | iteration 실패 기록 + 다음 정상 진행 |
| 7 | Evaluator JSON 파싱 실패 | verdict=null, 계속 진행, 최종 loop_exhausted 체크 |
| 8 | max_iterations=15 입력 시 clamp=10 | 실제 실행 iteration 상한 10 |
| 9 | operator=GT 경계값 | threshold 정확히 같을 때 통과 안 됨 |
| 10 | generator/evaluator 같은 model 경고 로그 | WARN 레벨 로그 1건 |

### 10.2 통합 테스트 — `WorkflowEngineEvaluatorLoopIT` (신규 3 케이스)

| # | 케이스 | 검증 포인트 |
|---|--------|------------|
| 1 | LLM_CALL → EVALUATOR_LOOP → LLM_CALL 체인 | dependsOn 정상 동작, `{{evalStep.output}}` 후속에서 참조 |
| 2 | PARALLEL 내부 EVALUATOR_LOOP 여러 개 | 병렬 실행 + 각각 독립 iteration |
| 3 | EVALUATOR_LOOP 실패 후 onFailure 라우팅 | 연속 3회 Gen 실패 → 스텝 FAIL → onFailure 스텝 실행 |

### 10.3 FE 단위 테스트 (신규 3 케이스)

- `EvaluatorLoopPanel.test.tsx` — 탭 전환 + 프롬프트 템플릿 드롭다운 로드
- `EvaluatorLoopNode.test.tsx` — 노드 렌더링 + dependsOn 엣지 연결
- `useWorkflows.test.ts` (기존 확장) — EVALUATOR_LOOP 포함 워크플로우 POST/PUT 라운드트립

### 10.4 커버리지 목표
- `EvaluatorLoopStepExecutor` 라인 커버리지 85%+
- 전체 CR-055 범위 신규 라인 기준 80%+

---

## 11. 마이그레이션 및 배포

### 11.1 DB 마이그레이션
- **Master DB**: 없음
- **Tenant DB**: `V{next}__cr055_evaluator_prompts.sql` — prompt_templates seed 3종 × 2 locale = 6 rows INSERT

### 11.2 배포 순서
1. BE 배포 (StepType enum 확장 + EvaluatorLoopStepExecutor) — 기존 워크플로우 무영향
2. Flyway 마이그레이션 자동 적용 (prompt_templates seed)
3. FE 배포 (WorkflowStudio 노드 팔레트 확장)

### 11.3 호환성
- **하위 호환**: 기존 워크플로우 100% 영향 없음. `EVALUATOR_LOOP`는 신규 enum 값이므로 기존 JSONB 데이터에 존재하지 않음.
- **롤백**: BE만 롤백해도 기존 스텝들은 정상 동작. EVALUATOR_LOOP 스텝 포함 신규 워크플로우만 실행 실패 (명시적 에러: `StepExecutor not found`).

---

## 12. 구현 순서 (Sprint 52 계획)

| Phase | 작업 | 산출물 | 예상 공수 |
|-------|------|--------|----------|
| 1 | BE: StepType enum 확장 + StepContext.loopVars | 1 commit | 0.5일 |
| 2 | BE: EvaluatorLoopStepExecutor 구현 + pass_criteria 3종 | 1 commit | 1.5일 |
| 3 | BE: WorkflowService.validate() 규칙 추가 | 1 commit | 0.5일 |
| 4 | BE: 단위 + 통합 테스트 | 1 commit | 1일 |
| 5 | DB: Flyway 마이그레이션 (prompt_templates seed 3종) | 1 commit | 0.5일 |
| 6 | FE: 타입 + 팔레트 + 커스텀 노드 컴포넌트 | 1 commit | 1일 |
| 7 | FE: EvaluatorLoopPanel 속성 편집 UX | 1 commit | 1.5일 |
| 8 | FE: WorkflowRunDetail iteration 확장 뷰 | 1 commit | 0.5일 |
| 9 | 문서: T3-1/T3-2/운영 가이드 반영 | 1 commit | 0.5일 |
| 10 | E2E: 문학 번역 시나리오 데모 워크플로우 시드 | 1 commit | 0.5일 |

**합계**: 약 8일 (1.5 스프린트 분량)

---

## 13. 결정 완료 사항 (2026-04-23 승인)

### Q1. Evaluator 실패 재시도 정책 — ✅ 확정
**결정**: Evaluator 호출만 **1회 재시도**. 재시도 후에도 실패 시 해당 iteration을 `verdict=null`로 기록하고 다음 iteration 진행.

**근거**: Generator 출력은 이미 생성된 비싼 자산. Evaluator 네트워크 일시 장애로 버리는 건 낭비. 재시도 1회는 지연·비용 영향 미미(evaluator 호출은 max_tokens 1K 수준).

**구현 반영**:
- `EvaluatorLoopStepExecutor`의 의사 코드 수정: evaluator 호출을 `try(1회) → catch → retry(1회)` 래퍼로 감싼다
- 재시도 트리거 조건: 네트워크 타임아웃 / 5xx / JSON 파싱 실패 ❌ (파싱 실패는 재시도해도 동일 결과, 즉시 verdict=null)
- 재시도 경로는 `iterations[i].evaluator_retried: true` 플래그로 감사 로그 구분
- Generator 실패 재시도 정책은 § 4.4 원안 유지 (연속 3회 실패 시 전체 스텝 FAIL)

**의사 코드 갱신** (§ 4.3 evaluator 호출 구간):
```
evalResult = null
try:
    evalResult = llmExecutor.execute(evalStep, evalCtx)
except TransientException:
    log.warn("Evaluator transient failure, retrying once...")
    try:
        evalResult = llmExecutor.execute(evalStep, evalCtx)
        iterRecord.evaluator_retried = true
    except:
        verdict = null  # 포기, 다음 iteration으로
```

### Q2. iteration 이력 저장 방식 — ✅ 확정
**결정**: `workflow_runs.step_results` JSONB 내부 `iterations[]` 배열 누적 (§ 2.3 원안 유지). 별도 테이블 신설하지 않음.

**근거**: BIZ-110으로 max_iterations 상한 10 확정 → JSONB 크기 예측 가능(1 iteration ≈ 5KB, 최대 50KB). 별도 테이블은 YAGNI. 감사/검색 요구가 강해지면 후속 CR에서 분리 고려.

**구현 반영**: § 2.3, § 4.3, § 10.1 모두 원안 그대로 진행. DB 마이그레이션 영향 없음 재확인.

**향후 분리 트리거 (참고용)**:
- iteration 단위로 SQL 검색/집계 요구가 발생
- iteration 하나가 10KB를 초과하는 케이스가 반복 발생
- 감사 규정상 iteration별 독립 감사 레코드 요구

위 중 하나가 실무에서 확인되면 `workflow_run_iterations` 테이블 분리 CR 신규 발행.

---

## 14. 최종 승인 체크리스트

- [x] Q1 Evaluator 실패 재시도 정책 확정 (1회 재시도, verdict=null fallback)
- [x] Q2 iteration 이력 저장 방식 확정 (JSONB 유지)
- [x] 최종 승인 — **Sprint 52 착수 가능** (Phase 1부터 순차)

**작성**: Claude (CR-055 설계 담당)
**검토 요청자**: sykim1850
**승인일**: 2026-04-23
**승인 상태**: ✅ 최종 승인 완료 — 구현 착수 대기

---

## 15. 구현 중 설계 보정 이력 (Sprint 52, 2026-04-23 ~ 2026-04-24)

구현 Phase 6~8 진행 중 기존 FE 구조 조사 과정에서 세 가지 축소 결정이 내려졌다. 모두 **MVP 범위 유지 + 과설계 회피** 관점에서 타당하며, 실사용 피드백 후 확장 여지는 열어둔다.

### 15.1 FE 노드 컴포넌트 — 별도 `EvaluatorLoopNode.tsx` 미채택
- **원안(§ 7.1)**: React Flow 커스텀 노드 `EvaluatorLoopNode.tsx` 신규 컴포넌트
- **실제**: 기존 [WorkflowNode.tsx](../frontend/src/components/workflow/nodes/WorkflowNode.tsx)의 `TYPE_STYLES` 맵에 `evaluator_loop`/`EVALUATOR_LOOP` 스타일 2줄 추가로 해결
- **사유**: 기존 `WorkflowNode`가 모든 노드 타입을 스타일 맵으로 처리하는 구조라 별도 컴포넌트는 코드 중복. 고유 시각화(예: 루프 영역 강조)가 필요해지면 그때 분리

### 15.2 FE 속성 패널 — 3탭 UI → 3 JSON 필드
- **원안(§ 7.3)**: Generator / Evaluator / 종료 조건 3개 탭 + 프롬프트 템플릿 드롭다운 + 슬라이더 등
- **실제**: 기존 `ConfigPanel`의 `getConfigFields()` switch에 `EVALUATOR_LOOP` 케이스 추가 → 4개 필드(`max_iterations` + `generator`/`evaluator`/`pass_criteria` 3개 multiline JSON). `JSON_CONFIG_KEYS`에 3개 키 추가해 저장/로드 자동 직렬화
- **사유**: 기존 패널이 **타입별 flat 필드 리스트** 구조라 3탭 UI는 패널 전체 구조 변경이 필요. placeholder에 완전한 JSON 예시를 넣어 UX 손실 최소화. 실사용 피드백으로 JSON 편집 불편함이 누적되면 전용 패널로 업그레이드

### 15.3 프롬프트 seed — 한/영 2 locale → 영문 3종만
- **원안(§ 8)**: `locale=ko_KR` / `en_US` 각 3종 = 6 rows
- **실제**: V53 마이그레이션에서 `language='en'` 3 rows만 seed ([V53__cr055_evaluator_prompts.sql](../backend/platform-core/src/main/resources/db/migration/tenant/V53__cr055_evaluator_prompts.sql))
- **사유**: 기존 V47 seed가 CR-036 정책에 따라 영문만 저장하는 관례. 한국어 버전이 필요한 테넌트는 `version=2`로 운영 UI에서 추가하거나, 테넌트별 커스터마이징 경로로 관리. 초기 seed는 영문을 "single source of truth"로 유지
- **실제 컬럼**: `language` VARCHAR(10) (기존 테이블 스펙), `locale` 아님

### 15.4 영향 없는 부분 (원안 그대로 구현)
- StepType enum 확장 + StepContext.loopVars (Phase 1)
- EvaluatorLoopStepExecutor의 실행 흐름, pass_criteria 3종, 재시도 정책 (Phase 2)
- WorkflowValidator의 검증 규칙 7가지 (Phase 3)
- WorkflowRunDetail iteration 확장 뷰 (Phase 8) — `EvaluatorLoopDetail` 내장 컴포넌트로 구현

