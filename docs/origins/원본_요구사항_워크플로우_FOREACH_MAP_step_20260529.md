# 요구사항 원본: 워크플로우 FOREACH/MAP step (컬렉션 fan-out)

- **요청자**: sykim
- **요청일**: 2026-05-29
- **출처**: Claude Code 대화 세션 (bidding-agency CR-013 패턴 추출 설계 중 식별)
- **발번 CR 번호**: CR-087 (CR-086 은 X-Tenant-Id 헤더 신뢰 경계 건에 선점되어 다음 번호로 확정)

---

## 배경

bidding-agency(SAM.gov 입찰 대행) CR-013 "성공 제안서 패턴 가이드" 구현 중, Aimbase 워크플로우로
다음을 처리하려다 빌딩블록 부재를 발견:

> **하나의 슬롯(정규화 축)에 모인 N개 성공 제안서 파일을 각각 parse_document로 텍스트화한 뒤,
> 합쳐서 LLM으로 "공통 패턴"을 추출한다.**

즉 "**동적 컬렉션의 각 원소에 같은 처리를 적용(map/fan-out)**"이 필요하다. 그러나 현재
`StepType`(`backend/platform-core/src/main/java/com/platform/workflow/model/WorkflowStep.java`)에는
선언적 컬렉션 반복 step이 없다:

```
LLM_CALL, TOOL_CALL, ACTION, CONDITION, PARALLEL, HUMAN_INPUT,
SUB_WORKFLOW, AGENT_CALL, EVALUATOR_LOOP, ROUTER
```

- `PARALLEL`(`branches`) = **컴파일 타임 고정 분기**. 런타임에 정해지는 N개 원소 map 아님.
- `cyclic` 모드 + `ROUTER` (CR-084) = 순환을 **수동 배선**. "각 원소에 map"의 선언적 추상이 아니며,
  컬렉션 인덱스 관리·종료조건을 워크플로우 작성자가 직접 짜야 해 복잡하고 재사용성이 낮음.
- `SUB_WORKFLOW` = 하위 워크플로우 호출. "N번 호출"의 fan-out 주체가 여전히 없음.

## 왜 범용 기능인가 (일회성 아님)

"동적 컬렉션 fan-out"은 LLM 오케스트레이션의 기본 빌딩블록이다. LangGraph의 `Send`/map,
타 워크플로우 엔진의 `forEach`/`map` step이 모두 이걸 위한 것. Aimbase 사용처 예:

1. **bidding-agency CR-013** — 슬롯에 모인 N개 제안서 파일 각각 parse → 합쳐 패턴 추출 (즉시 필요)
2. **bidding-agency B작업** — 제안서 50~100p를 N개 섹션으로 쪼개 **섹션별 LLM 생성** (예정)
3. 공고 N건 일괄 분석 / 요구사항 N개 각각 검증 / 문서 N개 각각 요약 등

현재는 각 소비앱이 자기 백엔드(Java 등)에서 루프를 돌려야 하므로 **재사용 안 됨** — 같은 fan-out
로직을 앱마다 재구현. Aimbase 워크플로우 step으로 두면 모든 소비앱이 선언적으로 공유.

## 제안 스펙 (초안 — Aimbase 측 설계 확정)

신규 StepType **`FOREACH`** (또는 `MAP`):

```json
{
  "id": "parse_each_file",
  "type": "FOREACH",
  "config": {
    "items": "{{input.samples}}",          // 반복할 컬렉션 (List 참조)
    "item_var": "sample",                    // 각 원소를 바인딩할 변수명
    "body": {                                // 각 원소에 적용할 step (TOOL_CALL/LLM_CALL 등)
      "type": "TOOL_CALL",
      "config": {
        "tool": "parse_document",
        "input": { "url": "{{sample.downloadUrl}}" }
      }
    },
    "execution": "parallel",                 // parallel(기본) | sequential
    "output_channel": "parsed_texts",        // 결과 누적 채널 (CR-085 reducer 재사용)
    "reduce": "append"                        // 각 원소 결과를 List로 누적
  },
  "depends_on": ["fetch_samples"]
}
```

이후 step에서 `{{parse_each_file.output}}` 또는 채널 `{{parsed_texts}}`로 N개 결과 List 참조 →
LLM_CALL에 합쳐 넣어 패턴 추출.

**설계 고려사항 (Aimbase 측 판단):**
- 빈 컬렉션 / 단일 원소 / 대량(예: 100+) 처리 시 동작·한도 (run step budget과의 관계 — CR-084 max_total_steps)
- 원소 처리 실패 시 전략 (skip / stop / collect-errors) — errorHandling과 연계
- 중첩 FOREACH 허용 여부
- 기존 `output_channel`/`reduce`(CR-085 append/merge) 재사용 가능성 — 이미 List 누적 메커니즘이 있으므로 FOREACH 결과 수집에 자연스럽게 연결될 듯
- `dag` 모드에서 동작 가능한지(고정 그래프에 동적 N 펼침) vs `cyclic` 전용인지

## 임시 대응 (이 요구 반영 전)

bidding-agency CR-013은 FOREACH 도입 전까지 **소비앱(bidding-agency) 백엔드에서 파일별
parse_document를 호출해 텍스트를 수집한 뒤 합쳐 워크플로우에 넘기는** 방식으로 우회 가능.
FOREACH step이 생기면 그 우회 로직을 제거하고 워크플로우로 일원화.

## 관련

- CR-084 (cyclic 모드 + ROUTER), CR-085 (channel reducer append/merge) — FOREACH가 재사용할 토대
- `parse_document` url 파라미터 (2026-05-29 추가) — FOREACH body의 TOOL_CALL 대상
- bidding-agency CR-013 — 첫 사용처
