# 원본 요구사항 — AGENT_CALL response_schema + 도구 루프 결합 (CR-088)

날짜: 2026-05-30
출처: 소비앱(bidding-agency) 제안서 자동 생성 워크플로우 설계 중 발견

## 배경

bidding-agency 제안서 생성 워크플로우를 섹션 루프로 재설계 중:

```
FOREACH (섹션 목록)
  └─ body: 초안 작성 (PDF 자율 열람 필요) + TipTap 구조화 출력
```

제안서는 공고문(영문 PDF, PWS 등)을 참조해야 하는데 **공고문이 그때그때 다름** → 정형 추출 불가 → AI가 PDF를 자율적으로 열어가며 판단해야 함 (AGENT_CALL 필요).

동시에 FOREACH `collect:merge`(CR-087)로 섹션 결과를 TipTap 문서 1건으로 병합하려면 각 섹션 body 출력이 `structured_data.content[]` 형태로 보장돼야 함 (response_schema 필요).

## 발견된 문제

소비앱 측 실측:
1. AGENT_CALL config가 `response_schema` 키를 받지 않음 (description/prompt/model/connection_id/isolation/timeout_ms만 지원).
2. AnthropicAdapter에서 `responseSchema`와 `tools`가 `if/else if` 상호배타로 처리됨 — 둘 중 하나만 활성.

Aimbase 측 추가 실측:
3. 더 깊은 문제 — **도구 루프 경로(`OrchestratorEngine.executeLoop`)가 resolvedSchema를 어댑터까지 전달하지 못함.** 어댑터를 결합으로 풀어도 schema가 도달하지 못해 의미 없음.

## 사용자 요구

AGENT_CALL이 도구 자율호출 + 구조화 출력(response_schema)을 **한 호출에서 동시에** 받을 수 있게 Aimbase 엔진 수정.

## 검토된 대안

| 안 | 내용 | 결정 |
|---|---|---|
| 옵션 1 | AGENT_CALL은 텍스트 출력, 별도 변환 스텝 추가 | 보류 |
| 옵션 2 | AGENT_CALL prompt에 "JSON으로 답하라" + fallback 파싱 | 비채택 (신뢰성 낮음) |
| 옵션 3 | FOREACH body=SUB_WORKFLOW(AGENT_CALL → LLM_CALL 2단계) | 백업안 |
| **CR-088** | **Aimbase 엔진 정공 수정** | **채택** |

## CR-088 작업 범위 (실측 기준)

| # | 파일 | 작업 |
|---|---|---|
| ① | AgentCallStepExecutor.java | config에서 response_schema 읽기 |
| ② | SubagentRequest.java | responseSchema 필드 추가 |
| ③ | SubagentRunner.java + ChatRequest.java | ChatRequest로 schema 전달 |
| ④ | OrchestratorEngine + ToolCallHandler | executeLoop 시그니처에 resolvedSchema 추가, 도구 루프 경로 schema 전파 |
| ⑤ | AnthropicAdapter.java | if/else if → 결합 (진짜 도구 + structured_output tool 함께), tool_choice 정책 |
| ⑥ | (다른 어댑터) | 본 CR은 Anthropic 우선. OpenAI/Bedrock/Vertex는 후속 |
| ⑦ | ToolCallHandler | structured_output tool 호출되면 루프 종료 + 결과 추출 |

추정 공수: 3~5MD.

## 알려진 트레이드오프

- 모델이 "도구 다 쓰고 마지막에 structured_output으로 마무리"를 항상 따르진 않음 → 프롬프트 가이드 강화 필요
- 다른 어댑터(OpenAI/Bedrock/Vertex/CLI)는 본 CR 범위 밖. 동일 결합 처리는 후속 CR

## 사용자 결정

- 핑퐁(EVALUATOR_LOOP) 자동 품질 보강은 불필요 (사람이 최종 검토)
- 옵션 3 백업안 대신 본 CR(정공)로 진행
- Anthropic 어댑터 우선

## 사용자 발언 (요약)

> "이게 공고문이 그때그때 다르기때문에 agent_call이 맞을수도 있어요"
> "사람이 봐야합니다." (자동 핑퐁 불필요)
> "받도록 aimbase수정하면안되나요?"
> "암튼, 요청하는것은 수정이 가능한지입니다."
> "진행하세요"
