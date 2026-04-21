# 원본 요구사항 — CR-054: Aimbase 플랫폼 공통 HttpRequestTool

**대화 일자**: 2026-04-22
**관련 CR**: CR-054 (HttpRequestTool), 후속 CR-055 (FlowGuard L2 자동 등록)
**Plan 파일**: `~/.claude/plans/l2-radiant-bachman.md`

---

## 발단 — FlowGuard L2 시나리오 수동 등록

> **사용자**: WMS 워크스페이스에 L2 시나리오를 등록해줘

(생략 — Claude Code가 Pilot으로 warehouse CRUD 4개 Step + 시나리오 1개를 FlowGuard REST API로 수동 등록. 3/4 PASS)

**Pilot에서 확정된 규약**
- `apiSpecRef`는 dsl 내부에 위치
- stepsConfig는 stepId(UUID) 사용
- varMapping 체이닝 `{{steps.<stepKey>.output.<var>}}`
- 시나리오 생성 직후 상태가 이미 ACTIVE인 경우 activate 호출 시 FG-8007

---

## 전환점 — "이걸 Aimbase 워크플로우로 자동화할 수 없나?"

> **사용자**: 위의 내용은 클로드코드를 사용하여 flowguard에 시나리오를 등록하는 절차입니다. 이걸 우리 aimbase를 통해서 진행하려면 워크플로우를 어떻게 작성해야할까요?

**탐색 결과 (Explore agent)**
- Aimbase 워크플로우는 8개 노드 타입(LLM_CALL, TOOL_CALL, ACTION, CONDITION, PARALLEL, HUMAN_INPUT, SUB_WORKFLOW, AGENT_CALL) 지원
- StepContext로 `{{input.x}}`, `{{stepId.structured_data.y}}` 변수 치환
- **범용 HTTP Tool 없음** — FlowGuardTool, HttpRequestTool, ApiCallTool 등 REST API 임의 호출 수단 부재
- MCP 서버 프레임워크만 있고 FlowGuard 등록 없음
- FlowGuard 관련 Connection seed 없음

---

## 선택지 제시 — 1차 AskUserQuestion

1. FlowGuard MCP 서버 등록 (Recommended)
2. 범용 HttpRequestTool 신규 개발
3. FlowGuard 전용 Tool 세트 개발

**사용자 선택**: 1 (FlowGuard MCP 서버 등록)

---

## 실수 정정 — FlowGuard는 MCP 없음

Claude가 "FlowGuard MCP 서버 등록"을 "Aimbase 내부에 FG 전용 MCP 구축"으로 독단적으로 재해석하여 Plan 1안 작성.

> **사용자**: flowguard mcp를 호출한다고 하지 않았나요?

**재확인 결과**
- FlowGuard `application.yml:26`에 "CR-026: spring.ai.mcp 제거됨" 주석
- 별도 `mcp-server/` TS 프로젝트도 디렉토리 부재 (CR-026에서 완전 제거)
- FlowGuard는 **순수 REST + API Key** 인증만 제공

**2차 AskUserQuestion**
1. Aimbase 내부 FG 전용 MCP 서버 구축 (Recommended)
2. FlowGuard에 MCP 서버 복원 (CR 신규)
3. 논리적 MCP 기입 + REST 호출 실장
4. 부유소의 MCP 세부사항 신경 쓰지 말고 동작만 구현

**사용자 선택**: 1 (Aimbase 내부 FG 전용 MCP 서버 구축)

---

## 재정정 — "FG 업무는 FG에서"

> **사용자**: 왜 저걸 aimbase에서 하나요 fg업무인데. fg에 mcp를 넣는게 낫지 않나요?

**Claude 수용**: 사용자 말씀이 정방향. FG 업무는 FG가 노출하는 것이 책임 경계상 깔끔. Aimbase가 대행하면 FG 규칙 변경마다 이중 유지보수 필요.

---

## CR-026 제거 사유 공유 + 범용 Tool 제안

> **사용자**: 기존건 mcp를 없앤 이유는 클로드코드가 fg mcp연결시 50% 확률 연결이 안되는 이슈가 많아서였어요. 그런데 지금은 대상이 aimbase라 문제가 없을것으로 판단이 되긴합니다. 그리고 한편으로는 aimbase가 범용 http 호출 툴이 없다고해서. 차라리 이번기회에 만들까라는 생각도 듭니다.

**Claude 분석**
- CR-026 제거 사유는 **stdio transport** 불안정성. Aimbase(Spring) ↔ FG(Spring) SSE/Streamable HTTP는 다른 문제
- 범용 HttpRequestTool 쪽이 전략적 가치 훨씬 큼 (FG 한정 MCP 복원 vs 모든 REST 연동 기반)

---

## 결정타 — MCP vs REST 본질 분석

> **사용자**: fg는 사실 mcp툴이라기보다 api가 더 맞을수도요. 왜냐하면 mcp는 llm이 선택적으로 사용하기 위함인데 현재 fg에서 제공하는 api들은 이미 명확한 기능들이거든요

**최종 수렴**
- MCP = LLM이 문맥 판단으로 도구 선택 (자율성 필요)
- REST = 프로그램이 결정론적으로 호출 (절차 확정)
- FG 등록 절차는 완전히 결정론적 → MCP는 과설계
- 오히려 LLM이 절차 선택에 개입하면 리스크 ↑ (Pilot에서 apiSpecRef 위치 착오 사례)

**최종 설계**
- **HttpRequestTool** 신규 (범용 자산)
- **결정론적 DAG**: TOOL_CALL + LLM_CALL(response_schema) + CONDITION
- **AGENT_CALL 제외** — LLM 판단은 "Step DSL 배치 생성" 딱 한 지점에만

---

## CR 분리

> **사용자**: 지금 2가지가 섞여 있는데 우선 http client 툴은 별도의 CR로 빼줄수 있나요?

**분리 결과**
- **CR-054**: Aimbase 플랫폼 공통 HttpRequestTool (본 Plan)
- **CR-055**: FlowGuard L2 시나리오 자동 등록 워크플로우 (후속)

CR 번호는 `docs/CR_변경_이력.md` 기준 최신 CR-053 다음으로 발번.

---

## 핵심 설계 요약 (CR-054)

| 항목 | 결정 |
|------|------|
| Tool 이름 | `http_request` |
| 카테고리 | `network` |
| Connection 타입 | `REST_API` 신규 |
| 인증 | API_KEY / BEARER / BASIC / NONE |
| 시크릿 관리 | `value_env` 환경변수 참조 |
| 응답 | 4xx/5xx 예외 없이 status 반환 (CONDITION 분기용) |
| 정책 | DomainFilterPolicy 초기 deny-all |
| 감사 | ToolCallAuditLogger + 헤더 마스킹 |
| 재시도 | Tool 내부 없음 — 워크플로우 retry 레벨 |
| 범위 경계 | FG Connection seed는 CR-055에서 |

---

## 메모리/규칙 준수

- **feedback_cr_origins** (전역 CLAUDE.md): CR 등록 시 docs/origins/에 대화 원본 저장 → 본 파일
- **feedback_design_cascade_first**: 기능 추가 전 규모 판단 + CR 확인 → Plan 모드에서 2차례 설계 전환 후 CR-054/055 분리
- **feedback_proactive_ux_suggestion**: 본 CR은 BE Tool 단독 (FE 영향 없음), 후속 CR-055에서 UI 고려

---

## 후속 작업

1. `docs/CR_변경_이력.md`에 CR-054 등재
2. HttpRequestTool 구현 착수 (별도 사용자 승인 후)
3. CR-055 Plan 작성 (다음 세션)
