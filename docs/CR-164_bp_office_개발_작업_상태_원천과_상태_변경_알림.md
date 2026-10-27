# CR-164: bp-office 개발 작업 상태 원천과 상태 변경 알림

- 발번: 2026-10-28
- 원본 요구사항: `docs/origins/원본_요구사항_CR164_개발_작업_상태_원천과_알림_20261028.md`
- 요청: bp-office CR-037 요청서 `docs/협업/aimbase_요청_CR-037_개발_작업_상태_보내기.md`(bp-office feat/SPR-10-dev adefc66). bp-office 가 Aimbase 의 개발 작업 상태를 받아 Leo 보고 자료로 저장하는 창구(`POST /api/v1/inbound/dev-agent-status`)를 만들고 있고, Aimbase 에는 보낼 원천도 보낼 자리도 없다
- 결정: bp-office CR-037 요청서 4절 ①(컨타 10/28 05:35, 사용자 결재 위임). 근거는 사용자 결정 「기존 개발 에이전트 = Aimbase」(bp-office `docs/origins/원본_요구사항_확인필요답변_20261024.md`)
- 규모: 중. 테넌트 표 2개, 개발 작업 API, 워크플로우 실행과 작업 항목 잇기, 상태가 바뀔 때 도는 워크플로우(이벤트 트리거). 화면 없음
- 상태: 설계

---

## 1. 요청서 3절

| 순번 | 바라는 것 | 이 CR 에서 |
|---|---|---|
| 1 | 개발 작업 상태의 원천(작업 번호, 기능 이름, 단계, 상태, 증빙 주소, 바뀐 시각) | 4.1 개발 작업 항목 |
| 2 | 상태가 바뀔 때 등록된 주소로 보내는 자리. 진행, 완료, 실패 모두. 머리값은 환경변수 참조, 본문은 요청서 2절 모양으로 짤 수 있게, `reportedAt` 은 바뀐 시각 그대로 | 4.3 상태 변경 워크플로우 |
| 3 | 실패해도 2 가 돈다 | 4.2 실행과 잇기, 4.4 실행 종료 신호 |
| 4 | `http_request` 가 연결 `auth.name` 머리값을 기록에서 가린다 | 4.5 |
| 5 | 실제로 한 번 보내 본다 | 7절(로컬 Aimbase 에서 로컬 bp-office 로) |

## 2. 조사 결과

2026-10-28 dev a655e1f 소스 기준이다.

1. 바깥으로 보내는 통로는 워크플로우 `TOOL_CALL` 단계의 `http_request` 다. HTTP 연결의 `config.auth`(`type=API_KEY`, `in=header`, `name`, `value_env`)로 머리값을 싣는다(`HttpRequestTool.java:303`). 응답 200 ~ 399 만 성공이고 그 밖은 단계 실패다(`HttpRequestTool.java:248`, `ToolCallStepExecutor` 가 실패를 예외로 올림)
2. 개발 작업 항목은 표로도 API 로도 없다
3. 실행 종료 신호(`WorkflowEvents.RunCompleted`)는 대부분의 종료 경로에서 나지만 두 곳이 빠져 있다. 승인 거절로 실패하는 run(`WorkflowEngine.java:412`), 단계가 없어 바로 완료하는 run(`:685`). 실행 시작 신호는 없다
4. 단계의 `onFailure` 는 엔진이 분기에 쓰지 않는다(`WorkflowEngine.java:1317` 은 `onSuccess` 만 본다). 워크플로우 안의 단계로는 실패를 보고할 수 없다
5. 워크플로우 `trigger_config` 의 `type` 은 엔진이 읽지 않는다. 관리 도구(MCP) 설명문이 안내하는 `webhook`, `schedule` 도 동작하지 않는다(`AimbaseAdminMcpConfig.java:67`). 예약 실행은 크론 작업(`CronScheduleManager`)이 따로 맡는다
6. `http_request` 의 기록용 머리값(`auditPayload.requestHeaders`)은 `authorization`, `x-api-key`, `cookie`, `proxy-authorization` 만 가린다(`HttpRequestTool.java:49`). 연결 `auth.name` 이 `X-Service-Token` 이면 값이 그대로 남고, 도구 직접 실행 API(`POST /api/v1/tools/{name}/execute`)가 이것을 응답에 싣는다
7. API 가이드 「4xx/5xx 도 CONDITION 분기」(`docs/guides/aimbase-api-guide.md:747`)는 1 과 다르다

## 3. 정한 방식

원천을 Aimbase 에 둔다(요청서 4절 ①). 상태는 두 길로 바뀐다. 작업을 돌린 쪽이 API 로 바꾸거나, 작업 항목을 실은 워크플로우 실행이 시작하고 끝날 때 엔진이 바꾼다. 보내는 일은 단계가 아니라 「상태가 바뀔 때 도는 워크플로우」가 한다. 실패한 run 안의 뒷단계에 기대지 않으므로 실패도 보낸다.

`onFailure` 분기를 엔진에 넣는 길은 쓰지 않는다. 실패를 보내려고 모든 개발 워크플로우에 실패 단계를 그려 넣어야 하고, 단계 밖에서 끝나는 실패(승인 거절, 중지, 예기치 못한 오류)는 그래도 못 보낸다.

## 4. 설계

규칙은 BIZ-129 ~ BIZ-132, 기능은 PRD-350 ~ PRD-353, 표는 T3-1 「dev_work_items」, API 는 T3-2 「개발 작업 API」, 단위시험은 T3-5 TC-DW-101 ~ 118.

### 4.1 개발 작업 항목 (PRD-350)

테넌트 표 두 개.

- `dev_work_items`: 작업 하나의 지금 상태. 키는 `work_item_id`(100자 이하, 부르는 쪽이 정함)
- `dev_work_item_changes`: 상태가 바뀔 때마다 한 줄. 바뀐 시각 `changed_at` 이 그 변경의 `reported_at` 이다. 다시 보낼 때도 이 값을 쓴다

| 칸 | 뜻 | 제한 |
|---|---|---|
| work_item_id | 작업 번호 | 1 ~ 100자 |
| feature_name | 기능 이름 | 1 ~ 300자 |
| stage | 단계 이름(예: 기능, 실증, 배포). Aimbase 는 해석하지 않는다 | 1 ~ 50자 |
| status | 상태 | `IN_PROGRESS`, `COMPLETED`, `FAILED`, `CANCELLED` |
| evidence_url | 증빙 주소 | 없거나 http, https 주소 2,000자 이하 |
| changed_at | 바뀐 시각 | TIMESTAMPTZ, 밀리초까지 |

BIZ-129: 단계, 상태, 증빙 주소 셋 중 하나라도 지금 값과 다를 때만 변경 한 줄을 남기고 알린다. 같은 상태를 다시 보고하면 아무것도 하지 않고 지금 상태를 돌려준다. 그래서 부르는 쪽이 재시도해도 알림이 두 번 나가지 않는다.

### 4.2 상태를 바꾸는 길 (PRD-350, PRD-351)

1. API `POST /api/v1/dev-work-items/{work_item_id}/status` 본문 `{feature_name, stage, status, evidence_url}`. 없는 작업이면 만든다(`feature_name` 필수). 있으면 `feature_name` 을 생략할 수 있다. 출처 `API`
2. 워크플로우 실행 입력의 `_dev_work_item` 칸 `{work_item_id, feature_name, stage, evidence_url}` (BIZ-130)
   - 실행 시작(`WorkflowEngine.execute`, 재실행 포함)에 칸을 검사하고 틀리면 run 을 만들지 않고 400(`dev_work_item_invalid:<칸>`)
   - run 을 만든 뒤 `IN_PROGRESS`
   - run 이 끝나면 `completed` 는 `COMPLETED`, `failed` 는 `FAILED`, `cancelled` 는 `CANCELLED`. 승인 대기(`pending_approval`)는 진행 중으로 본다
   - 부모 run 이 있는 자식 run(SUB_WORKFLOW)은 잇지 않는다. 부모만 잇는다
   - 출처 `WORKFLOW_RUN`, 변경 줄에 run id 를 남긴다
3. 조회 `GET /api/v1/dev-work-items`(최근 바뀐 순), `GET /api/v1/dev-work-items/{work_item_id}`(변경 줄 포함)

### 4.3 상태 변경 워크플로우 (PRD-352)

워크플로우 `trigger_config` 에 이벤트 트리거를 더한다.

```json
{"type": "event", "event": "DEV_WORK_ITEM_STATUS_CHANGED"}
```

BIZ-131: 개발 작업 상태가 바뀌면(4.1 의 변경 한 줄이 생기면) 그 테넌트에서 이 트리거를 단 활성 워크플로우를 모두 실행한다. 입력은 아래와 같고, 본문 모양은 워크플로우의 `http_request` 단계가 `{{input.*}}` 로 짠다.

| 입력 칸 | 값 |
|---|---|
| work_item_id, feature_name, stage, status, evidence_url | 그 변경의 값. 증빙이 없으면 빈 글 |
| reported_at | 변경 줄의 `changed_at`, UTC 오프셋 붙은 ISO-8601(예 `2026-10-28T06:30:00.123Z`) |
| change_id | 변경 줄 id |
| source, source_run_id | 출처와, 실행에서 났으면 그 run id |

- 알림 워크플로우는 비동기로 돌고, 하나가 실패해도 다른 것과 상태 기록에 영향이 없다
- 알림 run 은 일반 run 과 같이 실행 기록에 남는다. 보내기에 실패하면 워크플로우 `error_handling` 재시도를 따르고, 그래도 실패하면 그 run 을 재실행(CR-116)한다. 재실행은 같은 입력을 쓰므로 `reported_at` 이 바뀌지 않는다
- 알림 run 의 입력에는 `_dev_work_item` 이 없으므로 다시 알림을 부르지 않는다

### 4.4 실행 종료 신호 빈자리 (PRD-351)

`RunCompleted` 가 빠진 두 경로(승인 거절 실패, 단계 없는 완료)에서도 신호를 낸다. 실행 화면의 실시간 구독도 이 두 경로에서 끝을 받게 된다.

### 4.5 `http_request` 머리값 가림 (PRD-353, BIZ-132)

연결 `auth` 가 `API_KEY` 이고 `in=header` 면 그 `name` 을 그 요청의 가릴 머리값에 더한다(대소문자 무시). 입력 `headers` 로 같은 이름을 실어도 가린다. 기존 가리는 이름 네 개는 그대로다. 보내는 요청에는 값을 그대로 싣는다.

### 4.6 bp-office 로 보내는 설정

연결(HTTP)

```json
{"id": "bp-office-inbound", "type": "HTTP",
 "config": {"baseUrl": "<bp-office 주소>",
            "auth": {"type": "API_KEY", "in": "header", "name": "X-Service-Token",
                     "value_env": "OFFICE_INBOUND_TOKEN_DEV_AGENT"}}}
```

워크플로우

```json
{"trigger_config": {"type": "event", "event": "DEV_WORK_ITEM_STATUS_CHANGED"},
 "error_handling": {"retryMaxAttempts": 3, "retryDelayMs": 5000},
 "steps": [{"id": "send", "type": "TOOL_CALL",
            "config": {"tool": "http_request",
                       "input": {"connection_id": "bp-office-inbound", "method": "POST",
                                 "path": "/api/v1/inbound/dev-agent-status",
                                 "body": {"workItemId": "{{input.work_item_id}}",
                                          "featureName": "{{input.feature_name}}",
                                          "stage": "{{input.stage}}",
                                          "status": "{{input.status}}",
                                          "evidenceUrl": "{{input.evidence_url}}",
                                          "reportedAt": "{{input.reported_at}}"}}}}]}
```

토큰 값은 Aimbase 서버 환경변수에만 둔다.

### 4.7 바꾸지 않는 것

- 단계 `onFailure` 분기(3절)
- 화면. 작업 항목은 API 와 워크플로우 실행으로만 바뀐다
- 관리 도구 설명문과 API 가이드는 2절 5, 7 의 틀린 안내를 소스에 맞게 고친다(문구만)

## 5. 영향 설계서

| 문서 | 바뀌는 것 |
|---|---|
| T1-1 | PRD-350 ~ PRD-353 |
| T1-3 | BIZ-129 ~ BIZ-132 |
| T1-5 | DevWorkItem 상태 |
| T1-6 | DevWorkItemStatusChanged(내부), WorkflowRunCompleted 트리거 조건 보정, 개발 작업 상태 알림(외부) |
| T1-7 | CR-164 작업 단위 |
| T2-1, T2-2 | 영향 없음(새 라이브러리, 규약 없음) |
| T3-1 | dev_work_items, dev_work_item_changes, workflows.trigger_config 이벤트 트리거 |
| T3-2 | 개발 작업 API, 실행 입력 `_dev_work_item`, 이벤트 트리거 |
| T3-3, T3-4 | 영향 없음(화면 없음) |
| T3-5 | TC-DW-101 ~ 118 |
| 가이드 | API 가이드(개발 작업 API, 이벤트 트리거, 747 보정), 운용 가이드(상태 알림 설정 시나리오) |

## 6. 시험

단위시험 T3-5 TC-DW-101 ~ 118. 다른 소비처 워크플로우와 연결이 지금처럼 도는 것은 기존 워크플로우 엔진, `http_request` 시험을 다시 돌려 지킨다.

결과(10/28): `com.platform.workflow.*`, `com.platform.tool.*` 432건 통과, 실패 0. 머리값 가림은 TC-DW-116 ~ 118 이 지킨다.

로컬 실서버에서 `http_request` 가 연결을 읽지 못해 모든 호출이 실패하는 것을 찾았다. 생성자가 둘인데 표시가 없어 Spring 이 기본 생성자를 골랐고 연결 저장소가 비어 있었다. 기본 생성자는 2026-04-24 커밋 835d8d5 가 "프록시/스파이 지원" 으로 더했고 그때부터다. 같은 코드라 개발기와 운영의 `http_request` 도 같을 것으로 보지만 그쪽에서는 확인하지 않았다. 쓰일 생성자에 `@Autowired` 를 달았고, Spring 이 실제로 연결 저장소를 넣는지 보는 시험 `spring_context_wires_connection_repository` 를 더했다. 이 시험은 고치기 전 코드에서 실패한다.

## 7. 로컬 확인과 실제 보내 보기

받는 곳: 맥 bp-office 로컬 서버 `localhost:8253`(bp-office feat/SPR-10-dev, CR-037 이 기동). 토큰은 맥 비밀 파일의 `OFFICE_INBOUND_TOKEN_DEV_AGENT` 를 로컬 Aimbase 기동 환경변수로만 싣는다. 개발기 Aimbase 는 맥에 닿지 않아 로컬 Aimbase 에서 보낸다.

로컬 환경

| 항목 | 값 |
|---|---|
| Aimbase | 작업 사본 bootRun, 포트 8251(포트 레지스트리 등록), Redis 16164 |
| 시험 테넌트 | `cr164_local`(테넌트 마이그레이션 V74까지) |
| 연결 | `bp-office-inbound`(localhost:8253), `cr164-self`(localhost:8251, 가림 확인용). 둘 다 `auth` 는 `API_KEY`, 머리값 `X-Service-Token`, `value_env=OFFICE_INBOUND_TOKEN_DEV_AGENT` |
| 워크플로우 | `wf-cr164-dev-status`(트리거 `event`/`DEV_WORK_ITEM_STATUS_CHANGED`, 재시도 3회 5초), `wf-cr164-fail-demo`(없는 주소를 불러 실패하는 수동 실행) |
| 로컬 Postgres | 접속 한도 100 에서 200 으로 올림(`postgresql.conf` 65행, 컨타 승인 10/28 06:25). Aimbase 한 대가 테넌트마다 쉬는 접속 2개를 잡아 두 대가 뜨면 100 을 넘는다 |

로컬 확인

| 항목 | 결과 |
|---|---|
| `http_request` 연결 읽기 | 고친 뒤 `cr164-self` 로 `GET /actuator/health` 200, 도구 성공 |
| 머리값 가림 | 감사 기록 `requestHeaders` 에 연결 머리값 `X-Service-Token` 과 입력 머리값 `x-service-token` 이 모두 `***`. 다른 머리값은 그대로. 토큰 값과 입력한 평문은 응답 어디에도 없다 |

실제 보내기(10/28 06:50 KST, 받는 곳 `localhost:8253`)

| 순번 | workItemId | 상태 | 바뀐 곳 | reportedAt(UTC) | 알림 실행 | bp-office 응답 |
|---|---|---|---|---|---|---|
| 1 | CR164-DEMO-01 | IN_PROGRESS | API | 2026-10-27T21:50:16.033Z | a5ad76dd completed | 200, inboundEventId 100, PROCESSED, duplicate false |
| 2 | CR164-DEMO-01 | COMPLETED | API(증빙 주소 포함) | 2026-10-27T21:50:21.635Z | 2d065c3e completed | 200, inboundEventId 101, PROCESSED, duplicate false |
| 3 | CR164-DEMO-02 | IN_PROGRESS | 실행 357b59f4 시작 | 2026-10-27T21:50:24.022Z | c189ba2f completed | 200, inboundEventId 102, PROCESSED, duplicate false |
| 4 | CR164-DEMO-02 | FAILED | 실행 357b59f4 실패(단계가 3번 시도 뒤 400) | 2026-10-27T21:50:31.98Z | c4d81163 completed | 200, inboundEventId 103, PROCESSED, duplicate false |
| 5 | CR164-DEMO-02 | FAILED | 4번 알림 실행을 06:51 에 다시 실행 | 2026-10-27T21:50:31.98Z(같은 값) | 0dd8f1f3 completed | 200, inboundEventId 103, duplicate true |

- 실행이 실패로 끝나도 FAILED 가 알림 실행을 새로 만들어 보내졌다(요청서 3절 3)
- 다시 보내도 `reportedAt` 은 바뀐 시각 그대로라 bp-office 가 중복으로 걸렀다(요청서 3절 2)
- 알림 실행 기록 어디에도 토큰 값이 없다

## 8. 개발기 반영

개발기(59.8.160.12) api 에 코드와 테넌트 표를 올린다. 개발기에는 알림 워크플로우와 bp-office 연결을 만들지 않는다. 받는 쪽 토큰을 개발기 환경에 넣는 일과 개발기끼리 보내기는 이번 범위 밖이다.

1. 작업 폴더 사본에 CR-164 파일 40개를 덮고 대조한다(CR-164 파일은 작업 사본과 같고, CR-161 파일은 dev 커밋 16468d6 과 같다. 다른 것은 작업 폴더의 미커밋 `OrchestratorEngine` CR-131 분뿐이고 개발기 실행본에 이미 든 것이다)
2. 사본에서 워크플로우, 도구, 개발 작업, LLM, 오케스트레이터 영역 시험
3. 서버의 지금 jar 를 `platform-core.jar.before-cr164` 로 복사해 둔다
4. 사본에서 `./deploy.sh be`(api 다시 만듦)
5. 테넌트 마이그레이션 V74(`dev_work_items`, `dev_work_item_changes` 새 표 2개). 기동 때 돌지 않으면 `POST /api/v1/platform/tenants/migrate` 로 활성 테넌트 전체
6. 확인: api health, 테넌트별 V74 적용, 실행기 다시 붙음, 개발 작업 목록 API 200

같이 실리는 것: dev 의 CR-161 api 변경(아직 개발기 미반영). CR-161 은 연결 묶음이 0건이라 지금 동작이 바뀌지 않는다고 적었다(CR-161 2.1).

되돌리기: 3의 jar 로 바꿔 api 를 다시 만든다. V74 표는 새 표뿐이라 남겨도 다른 기능에 닿지 않는다.

## 9. 경과

| 일자 | 내용 |
|---|---|
| 10/28 | 발번. 조사와 설계 |
