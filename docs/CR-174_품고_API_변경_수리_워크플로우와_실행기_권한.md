# CR-174: 품고 API 변경 수리 워크플로우와 실행기 권한

- 발번: 2026-11-09
- 원본 요구사항: `docs/origins/원본_요구사항_CR174_품고_API_변경_수리_워크플로우와_실행기_권한_20261109.md`
- 요청: 컨타 3plcore-58662 브리핑(11-09 14:02). 3PLcore 「품고 API 변경 자동 대응」의 aimbase 몫. 공통 규칙 `3PLcore/docs/40_브리핑/품고API변경자동대응_공통규칙.md`. 설계 주인은 bp-3plgw 고, 이 레포는 피호출자라 웹훅 입력 형식만 정한다
- 결정(결정 메모 원문 2026-11-09): 10:15 Claude 는 운영 chaws 의 Aimbase 실행기로 띄운다, GitHub Actions 와 API 키는 쓰지 않는다. 10:17 완전 자동이 아니다, 사람에게 통보하고 운영 반영은 승인 뒤에 한다. 10:33 승인은 BO2 앱에서 한다. Claude 는 제안만 하고 운영 DB 에 쓰지 않는다
- 앞 건: CR-161(워크플로우 단계의 연결 묶음, bp 계열 계정 셋), CR-162(운영 chaws Aimbase), CR-164(실행 종료 신호, 이벤트 트리거, http_request 머리값), CR-173(소비앱 전용 실행기와 설치 스크립트 대상)
- 규모: 중. 엔진에 실행 종료 회신(`_callback`), 승인 시한 감시, 단계 변수 `{{run.*}}` 이 새로 들고, 테넌트 `bp_3plgw`, 실행기 컨테이너, 워크플로우 정의, 실행기 이미지(git, gh)가 새로 생긴다. 새 테이블, 새 화면, 마이그레이션 없음
- 상태: 설계중(2026-11-09 14:30 설계 캐스케이드와 계약 문서 작성. 3절 결정 2건은 컨타에 승인 요청)

---

## 1. 배경

품고가 예고 없이 화면이나 내부 API 를 바꾸면 bp-3plgw 수집기가 멈춘다. 3PLGW 가 변경을 잡아 Aimbase 의 수리 워크플로우를 부르고, Claude Code 가 3PLGW 레포와 개인정보를 지운 표본을 놓고 고친다. 이름만 바뀐 것은 새 계약 버전 제안, 구조나 화면이 바뀐 것은 코드 수정과 PR 이다. 3PLGW 가 제안을 검증하고 사람이 BO2 앱에서 승인하면, 계약 활성화나 PR 머지로 반영한다. 거절, 시한 초과, 실패는 3PLGW 에 결과만 돌려주고 사람 처리로 남긴다.

## 2. 조사 결과 (2026-11-09 소스와 59, chaws 실측)

| 순번 | 확인한 것 | 근거 |
|---|---|---|
| 1 | 웹훅 트리거는 없다. `trigger_config.type` 은 `event` 만 엔진이 읽는다. 바깥 앱이 워크플로우를 부르는 길은 `POST /api/v1/workflows/{id}/run` 이고, 테넌트 소비앱 키(`X-API-Key`)로 인증되며 run 을 바로 돌려주고 비동기로 돈다 | CR-164 2절 5, `WorkflowEngine.java:312`, `SecurityConfig.java:95`, `ApiKeyAuthenticationFilter.java:107` |
| 2 | 바깥에서 승인을 넣는 API 가 있다. `POST /api/v1/workflows/runs/{runId}/approve` 본문 `{approved, reason}`. 소비앱 키로 부를 수 있다. 거절이면 run 은 `failed` 로 끝나고 종료 신호를 낸다 | `WorkflowController.java:180`, `WorkflowEngine.java:402` |
| 3 | 승인 시한은 적기만 한다. HUMAN_INPUT 은 단계 `timeoutMs` 로 `pending_approvals.timeout_at` 을 적지만 지나도 보는 곳이 없다. PendingApproval 을 읽는 곳은 엔진, AdminController, Repository 뿐이다 | `WorkflowEngine.java:895`, `grep PendingApproval` |
| 4 | 단계 밖에서 끝나는 실패(승인 거절, 중지, 예기치 못한 오류)는 워크플로우 안의 단계로 보낼 수 없다. `onFailure` 는 엔진이 분기에 쓰지 않는다. 종료는 `RunCompleted` 하나로 모든 경로에서 난다 | CR-164 2절 4, 3절, 4.4 |
| 5 | 단계 틀 변수는 `{{input.*}}`, `{{steps.*}}`, `{{loop.*}}` 만이다. run id 를 단계 본문에 실을 수 없다 | `StepContext.java:170` |
| 6 | AGENT_CALL 은 `connection_id` 또는 `connection_group_id` 로 CLI 실행기를 부른다. 한도 응답(`cli_account_limited:`)은 그 멤버의 서킷 브레이커를 `resets_at` 까지 열고 다음 멤버로 넘어간다. 모든 멤버가 빠지면 단계가 실패한다 | `SubagentRunner.java:453`, CR-161 4.1, BIZ-128 |
| 7 | 도구 모드는 연결 `config.tool_mode` 다. AIMBASE 는 네이티브 Bash, Write 를 봉인하고, HYBRID 는 네이티브 Bash, Read, Edit 과 Aimbase 도구를 같이 준다. 실행기 cwd 는 `/data/workspace` 아래 작업장이다 | `ClaudeCliCommandBuilderTest.java:16`, `ClaudeCliRunnerClient.java:305` |
| 8 | 실행기 이미지에 git, gh 가 없다. Dockerfile 은 curl, poppler-utils, postgresql-client, node, python3 만 깐다 | 59 `aimbase-aimbase-agent-1` 에서 `which git gh` 없음, `backend/aimbase-agent/Dockerfile:12` |
| 9 | 59 에 Aimbase 개발기가 있다. api, 실행기 7개(bidding_system, axopm_companyA, workmap, bp_wes 둘, lexflow, bp_office), 계정 폴더 work01, work02, work03. 테넌트 9개에 `bp_wms`, `bp_3plgw` 는 없다. 3PLGW 개발기(bp-3plgw-api-1, poomgo-collector, fauxpoomgo)도 59 에 떠 있다 | 11-09 14:10 `docker ps`, master DB `tenants` 읽기 조회 |
| 10 | chaws 는 테넌트 `bp_wms` 와 실행기 `aimbase-agent`(cli-runner-bp-wms, 계정 work01 부터 work03, 일꾼 3, 읽기 전용 DB 계정 claude_ro), `bp_wes` CS 실행기다. 실행기 계정 폴더와 작업장을 소비앱마다 나눈다 | `docker-compose.chaws.yml:80`, CR-173 2절 |
| 11 | `http_request` 는 연결(HTTP)의 `baseUrl` 과 `path` 로 주소를 만들고, 연결 `auth.value_env` 로 서버 환경변수 값을 머리글에 싣는다. 절대 주소는 받지 않는다 | `HttpRequestTool.java:166`, `:352` |
| 12 | 실행 종료를 바깥과 잇는 선례는 `DevWorkItemRunBinding` 이다. 실행 입력의 예약 칸 `_dev_work_item` 을 run 생성 전에 검사하고 `RunCompleted` 를 `@EventListener` 로 받는다 | `DevWorkItemRunBinding.java:38`, `:50` |

## 3. 결정할 것

1. 테넌트와 실행기 자리
   1. 새 테넌트 `bp_3plgw` 와 전용 실행기 컨테이너 `aimbase-agent-3plgw`(권장). 작업장은 따로 두고 계정 폴더는 공용 `claude-accounts` 를 같이 쓴다(2026-11-09 16:03 컨타 회신. 격리는 env 로 나눈 GitHub 토큰, 내부 키, DB 자격으로 충분하고 토큰 갱신이 한 곳이어야 한다). CR-173 이 bp-wes CS 에 한 것과 같은 꼴이되 계정 폴더만 나누지 않는다
   2. chaws 의 `bp_wms` 테넌트와 `aimbase-agent` 를 그대로 쓴다(잃는 것: 수리 CLI 가 bash 로 같은 계정 폴더의 지난 대화와 claude_ro 접속값을 읽을 수 있고, GitHub 토큰이 bp-wms 가 부르는 CLI 에도 보인다)
2. GitHub 자격
   1. bp-3plgw 한 저장소에만 미치는 fine-grained 토큰(Contents 쓰기, Pull requests 쓰기, Metadata 읽기)을 사용자가 만들어 서버 env 파일에만 둔다(권장). 59 와 chaws 에 하나씩
   2. 배포 키(SSH)(잃는 것: PR 생성에 gh 가 토큰을 따로 요구해 비밀값이 둘이 된다)

사용자 승인 전까지 둘 다 1 로 설계한다.

## 4. 설계

### 4.1 흐름

```
3PLGW   POST /api/v1/workflows/poomgo-change-repair/run (X-API-Key: bp_3plgw 소비앱 키)
Aimbase run 을 만들고 run_id 를 바로 돌려준다. 아래 단계는 비동기로 돈다
  repair    AGENT_CALL   CLI(HYBRID, 묶음 work01 부터 work03). 3PLGW 레포를 작업장에 받고 표본을 대조해
                         계약 제안(CONTRACT) 또는 코드 수정과 PR(CODE, 라벨 deploy-59)을 만든다. 출력은 JSON 한 덩이
  submit    TOOL_CALL    http_request. 3PLGW 「제안 제출」(검증 포함)에 run_id 와 제안을 보낸다.
                         검증 실패는 4xx 라 단계가 실패하고 run 이 failed 로 끝난다
  approval  HUMAN_INPUT  승인 대기. 시한은 단계 timeoutMs. BO2 승인은 3PLGW 가 승인 API 로 넣는다
  apply     ROUTER       CONTRACT 이면 activate, CODE 이면 merge
  activate  TOOL_CALL    http_request. 3PLGW 「계약 활성화」
  merge     TOOL_CALL    http_request. GitHub PR merge
run 이 끝나면(completed, failed, cancelled) _callback 으로 3PLGW 에 결과를 한 번 보낸다
```

승인 거절, 시한 초과, 단계 실패, 중지는 모두 run 의 끝이라 `_callback` 한 길로 3PLGW 에 간다. 워크플로우 안에 실패 단계를 그리지 않는다(CR-164 3절과 같은 판단).

### 4.2 웹훅 입력 (Aimbase 가 정한다)

계약 문서 `docs/협업/계약_CR-174_품고_변경_수리_요청_웹훅.md` 가 정본이다. 요지만 적는다.

| 칸 | 필수 | 값 |
|---|---|---|
| `schema_version` | 예 | 이 입력 형식의 판. `"1"` |
| `change_id` | 예 | 변경 지문. 3PLGW 가 같은 값이면 다시 부르지 않는다. 가지 이름과 회신에 쓴다 |
| `change_kind` | 예 | `FORMAT`(형식 변경), `VALUE`(값 이상), `SCREEN`(화면 변경) |
| `source` | 예 | 원천 이름(주문, 송장, 입고요청서 등). 3PLGW 가 정한 글자 그대로 쓴다 |
| `detected_at` | 예 | 감지 시각. UTC 오프셋 붙은 ISO-8601 |
| `repo` | 예 | `url`(https 주소), `base_branch`(PR 의 바탕 가지), `commit`(감지 시점 수집기 커밋) |
| `contract` | 예 | `version`(지금 활성 계약 판), `url`(계약 내용 조회 주소) |
| `samples` | 예 | `broken_url`(어긋난 응답), `last_good_url`(마지막 정상 응답), `dom_dump_url`(화면 변경 때, 없으면 null). 모두 개인정보 제거본. 작은 JSON 은 `broken`, `last_good` 에 본문을 실어도 된다 |
| `summary` | 아니오 | 3PLGW 감지기가 쓴 한 줄 |
| `pr_labels` | 아니오 | PR 에 붙일 라벨. 없으면 `["deploy-59"]`(bp-infra CR-INFRA-008 59 배포 신호) |
| `_callback` | 예 | `connection_id`(Aimbase 의 3PLGW HTTP 연결 id, `bp-3plgw`), `path`(결과를 받을 경로), `result_step`(`repair`) |

응답은 `ApiResponse<run>` 이고 `data.id` 가 run_id 다. 3PLGW 는 승인과 조회에 이 값을 쓴다.

### 4.3 엔진 변경

#### 4.3.1 실행 종료 회신 `_callback` (PRD-363, BIZ-143)

실행 입력의 예약 칸 `_callback` 이 있으면 run 이 어떤 상태로든 끝날 때 그 연결의 그 경로로 결과를 POST 한다. 모든 워크플로우가 쓸 수 있는 공통 장치다.

- 검사: run 을 만들기 전에 `connection_id` 가 있는 HTTP 연결인지, `path` 가 `/` 로 시작하는지 본다. 틀리면 400 `callback_invalid:<칸>` 이고 run 을 만들지 않는다(`_dev_work_item` 과 같은 자리)
- 보내는 때: `RunCompleted`(completed, failed, cancelled) 를 `@EventListener` 로 받아 보낸다. 승인 거절, 시한 초과, 단계 실패, 중지, 단계 없는 완료가 모두 여기로 온다
- 본문: `run_id`, `workflow_id`, `status`, `error`(run 의 `error` 그대로, 없으면 null), `result`(`result_step` 단계의 `structured_data`, 없으면 `output`, 칸이 없으면 null), `completed_at`, `duration_ms`
- 머리글: 연결 `auth` 가 넣는다(`value_env`). 기록은 `http_request` 와 같이 가린다(BIZ-132)
- 재시도: 실패하면 `aimbase.workflow.callback.retry-max-attempts`(기본 3) 번까지 `retry-delay-ms`(기본 5000) 간격으로 다시 보낸다. 끝내 실패하면 run 이벤트(CR-090)에 `CALLBACK_FAILED` 를 남기고 경고 로그를 쓴다. run 상태는 바꾸지 않는다
- 구현 자리: `com.platform.workflow.callback.RunCompletionCallback`(`@Component`, `@EventListener`). 엔진은 `_dev_work_item` 처럼 `ObjectProvider` 로 받아 `validate` 만 부른다

#### 4.3.2 승인 시한 감시 (PRD-364, BIZ-144)

- `ApprovalTimeoutSweeper` 가 `aimbase.workflow.approval-timeout.sweep-ms`(기본 60000) 마다 캐시된 테넌트를 돌며(CR-122 의 `AttachmentGcScheduler` 와 같은 꼴) `pending_approvals` 에서 `status = pending`, `approval_channel = workflow`, `timeout_at < now` 인 줄을 찾는다
- 찾은 줄은 `status = timeout`, `resolved_at = now`. 그 run(`action_log_id`)이 `pending_approval` 이면 `failed`, `error = {reason: "approval_timeout", detail: "<timeout_at>"}`, `completed_at = now` 로 닫고 `RunCompleted(failed)` 를 낸다
- 이미 승인 API 로 풀린 run(`running`)은 건드리지 않는다. 승인 API 는 `pending` 줄만 바꾸므로 둘이 겹치지 않는다
- 시한은 워크플로우 정의의 HUMAN_INPUT 단계 `timeoutMs` 다. DB 에 저장되어 워크플로우 수정 API 와 스튜디오로 바꾸고 재기동이 없다. 단계에 `timeoutMs` 가 없으면 지금처럼 시한이 없다

#### 4.3.3 단계 변수 `{{run.*}}` (PRD-365)

`StepContext` 틀 치환에 `run` 이름 공간을 더한다. `{{run.id}}`, `{{run.workflow_id}}`, `{{run.session_id}}`. 제안 제출 단계가 run_id 를 3PLGW 에 실어 보내고, 3PLGW 가 그 값으로 승인 API 를 부른다.

#### 4.3.4 승인 대기 행의 외래키 제거 (V76)

로컬 확인에서 HUMAN_INPUT 단계가 승인 대기 행을 저장하지 못하고 run 이 `failed` 로 끝났다. `pending_approvals.action_log_id` 와 `policy_id` 는 워크플로우 승인에서 run id 와 단계 id 를 담는 칸인데(4.3.2, `handleHumanInput`), `action_logs`, `policies` 로만 묶인 외래키가 남아 있어 어느 테넌트에서도 워크플로우 승인 대기가 만들어진 적이 없었다(59 의 활성 테넌트 DB 전부 외래키 있음, 워크플로우 승인 대기 행 0건으로 확인). 이 CR 이전부터 있던 결함이고 승인 단계를 쓰는 첫 워크플로우가 이 CR 이라 여기서 고친다.

- `V76__cr174_pending_approvals_drop_action_log_fk.sql`: 두 외래키를 지우고 `action_log_id` 에 인덱스를 둔다. 열과 값의 뜻은 그대로다
- 59 는 api 기동 때 테넌트 마이그레이션이 돌아 `./deploy.sh be` 로 적용된다. 로컬은 `POST /api/v1/platform/tenants/migrate` 로 따로 돌린다

#### 4.3.5 CLI 응답에서 마지막 JSON 객체 추출 (2026-11-10, 59 건 3 실패로 추가)

CLI 자율 주행 응답의 본문은 도구를 쓰는 동안의 진행 설명과 마지막 JSON 이 이어 붙은 글이다. 어댑터 출구의 정규화는 첫 `{` 부터 마지막 `}` 까지를 잘라 JSON 으로 읽었으므로 진행 설명에 중괄호가 섞이면 실패해 `structured_data` 가 비고, `submit` 본문의 `proposal` 이 null 이 되어 3PLGW 가 400 을 냈다(run be3907d1). 고친 것: 마지막 펜스 안, 그다음 글 전체에서 뒤에서부터 객체로 읽히는 `{...}` 를 고르고, 둘 다 없으면 예전 방식으로 돌아간다(`StructuredOutputNormalizer.stripFence`, `lastObject`). 채팅 경로와 LLM_CALL 도 같은 출구를 지나지만 잘 읽히던 응답은 결과가 같다(시험 11건). 수리 단계는 `response_schema` 를 선언해야 이 길을 탄다(4.4).

### 4.4 워크플로우 정의 `poomgo-change-repair` (PRD-366)

테넌트 `bp_3plgw` 에 둔다. `graphMode: cyclic`, `entry_step: repair`, `error_handling: {retryMaxAttempts: 0}`(같은 변경에 수리는 한 번, BIZ-146), `output_schema` 없음.

| 단계 | 종류 | 설정 |
|---|---|---|
| `repair` | AGENT_CALL | `connection_group_id: cli-runner-bp-3plgw-rr`, `isolation: NONE`, `timeout_ms`(기본 1800000), `require_text_output: true`, `response_schema`(4.4.1 의 JSON 모양. 이것이 있어야 CLI 응답이 `structured_data` 로 들어간다. 2026-11-10 추가), `tools` 칸은 두지 않는다(빈 허용 목록은 도구를 전부 막는다. Aimbase 도구 노출은 연결 `exposed_tools` 가 정한다), 프롬프트는 4.4.1. `onSuccess: submit` |
| `submit` | TOOL_CALL `http_request` | 연결 `bp-3plgw`, `POST /api/v1/internal/poomgo-repair/changes/{{input.change_id}}/proposals`(3PLGW T3-2 2-1). 본문: `run_id {{run.id}}`, `change_id`, `proposal {{repair.structured_data}}`. 201 등록, 200 같은 run 재전송, 422 검증 실패와 404, 409 는 단계 실패(4xx, 5xx). `onSuccess: approval` |
| `approval` | HUMAN_INPUT | `message: "품고 변경 수리 제안 {{input.change_id}}"`, `reason: {{input.summary}}`, `timeoutMs`(기본 259200000, 72시간. 3PLGW 승인 시한 4시간보다 길게, 3PLGW 1-4). `onSuccess: apply` |
| `apply` | ROUTER | `routes: [{when: "{{repair.structured_data.result_kind}} equals 'CONTRACT'", to: activate}, {when: "{{repair.structured_data.result_kind}} equals 'CODE'", to: merge}]`. 둘 다 아니면 실패 |
| `activate` | TOOL_CALL `http_request` | 연결 `bp-3plgw`, `POST /api/v1/internal/poomgo-repair/changes/{{input.change_id}}/activate`(3PLGW T3-2 2-2). 본문: `run_id`, `change_id`, `contract_proposal`. 200 `{contractId, version, alreadyActive}`(BO2 가 승인 때 먼저 활성화했으면 `alreadyActive` true). 끝 |
| `merge` | TOOL_CALL `http_request` | 연결 `github-bp-3plgw`, `PUT /repos/{{repair.structured_data.pr.repo}}/pulls/{{repair.structured_data.pr.number}}/merge`, 머리글 `Accept: application/vnd.github+json`, 본문 `{merge_method: "squash"}`. 끝 |

#### 4.4.1 repair 단계가 하는 일

프롬프트는 워크플로우 정의 안에 둔다(코드가 아니다). 시키는 것은 아래다.

1. 작업장 `/data/workspace/bp-3plgw` 에 레포가 없으면 `git clone {{input.repo.url}}`, 있으면 `git fetch`. `{{input.repo.commit}}` 에서 가지 `fix/poomgo-{{input.change_id}}` 를 만든다. 자격은 `GH_TOKEN`. git 은 이미지에 박은 `credential.helper = gh auth git-credential` 이 그 토큰을 낸다
2. 계약 `{{input.contract.url}}` 과 표본(과 DOM 덤프)을 머리글 `X-Internal-Service-Key: $TPLGW_INTERNAL_KEY` 로 curl 해 대조해 어긋난 자리를 찾는다. `last_good_url` 이 404 면(그 원천의 첫 감지) 어긋난 표본과 계약만으로 간다
3. 이름만 바뀐 것(키 이름, 상태값, 선택자 글자)이면 코드를 고치지 않고 계약 spec 과 같은 모양(sourceCode, envelope, fields, valueMaps, screen)의 새 판을 `contract_proposal` 에 낸다. 레포에 표본 재생 시험 도구가 있으면 어긋난 표본을 새 판으로 돌려 필수 칸 채움률을 잰다
4. 구조나 화면이 바뀐 것이면 코드를 고치고 단위 시험과 표본 재생 시험을 통과시킨 뒤 가지를 밀어 올리고 `gh pr create --base {{input.repo.base_branch}} --label <pr_labels>` 로 PR 을 만든다
5. 운영 DB, 운영 서버, 다른 레포에 손대지 않는다. force push, merge, 가지 삭제를 하지 않는다. 비밀값을 출력에 적지 않는다
6. 마지막 응답은 JSON 한 덩이만 낸다(펜스 없이, 뒤에 글을 붙이지 않는다). 진행 설명이 앞에 붙어도 엔진이 마지막 JSON 객체를 고른다(4.3.5)

```json
{"result_kind": "CONTRACT | CODE | NONE",
 "summary": "한 줄",
 "changed": [{"kind": "FIELD_PATH | VALUE | REQUIRED | SCREEN", "name": "주문 번호", "before": "orderSheet.orderCode", "after": "orderSheet.orderNo", "change": "RENAMED | ADDED | REMOVED | MOVED"}],
 "contract_proposal": {"sourceCode": "ORDER", "envelope": {}, "fields": [], "valueMaps": {}, "screen": {}},
 "pr": {"number": 57, "url": "https://github.com/therecommerce-develop/3plgw/pull/57", "branch": "fix/poomgo-...", "title": "fix: 품고 ORDER 변경 대응 (ord-...)", "repo": "therecommerce-develop/3plgw"},
 "tests": {"unit": "passed | failed | skipped", "unit_passed": 41, "unit_total": 41, "replay_fill_rate": 0.98},
 "failure_reason": null}
```

모양은 3PLGW T3-2 2-1 제안 본문과 같다. `CONTRACT` 면 `contract_proposal` 이 차고 `pr` 은 null, `CODE` 면 `pr` 이 차고 `contract_proposal` 은 null. `pr.title` 은 3PLGW 승인 화면에 보이고(3PLGW 1-4), `pr.repo`(GitHub owner/name)는 merge 단계 경로에 쓴다. `result_kind: NONE` 은 어긋남을 못 찾았거나 고칠 수 없다는 뜻이고, submit 단계가 그대로 보내 3PLGW 가 사람 처리로 남긴다.

### 4.5 실행기와 권한 (PRD-367, BIZ-145)

| 항목 | 값 |
|---|---|
| 이미지 | `backend/aimbase-agent/Dockerfile` 에 git, gh, openssh-client 를 더한다. 모든 실행기가 같은 이미지라 다 갖게 되지만 토큰은 이 실행기에만 있다 |
| 컨테이너 | `aimbase-agent-3plgw`. 59 `docker-compose.prod.yml`, chaws `docker-compose.chaws.yml`. 실행기 이름 `cli-runner-bp-3plgw`, 테넌트 `bp_3plgw`, 일꾼 1, 포트 8304(실행기), 8305(MCP), `mem_limit 1536m`(chaws) |
| 환경 | 실행기: `AIMBASE_RUNNER_SERVER_MCP_API_KEY`, `AGENT_API_KEY` = `bp_3plgw` 실행기 전용 키(`BP_3PLGW_SERVER_MCP_API_KEY`), `GH_TOKEN`(GitHub 토큰), `TPLGW_INTERNAL_KEY`(3PLGW 내부 키. 계약과 표본 curl). PG 환경변수 없음. 59 는 `.env.runner-3plgw`, chaws 는 `agent-3plgw.env`. api: HTTP 연결 `value_env` 는 api 프로세스 env 를 읽으므로 `TPLGW_INTERNAL_KEY`, `GITHUB_TOKEN_3PLGW` 를 59 `.env`, chaws `api.env` 에도 둔다 |
| 볼륨 | `claude-accounts`(다른 실행기와 같은 폴더의 work01, work02, work03. 토큰 갱신이 한 곳이어야 한다), `workspace-3plgw` |
| 연결 | `cli-runner-bp-3plgw-work01`, `-work02`, `-work03`: `anthropic-cli`, `tool_mode HYBRID`, `config_dir` 그 계정, `agent_name cli-runner-bp-3plgw`, `exposed_tools ["get_current_time"]`, `model claude-sonnet-4-5`(설정). 묶음 `cli-runner-bp-3plgw-rr` ROUND_ROBIN |
| HTTP 연결 | `bp-3plgw`: `baseUrl` 59 도 운영도 `http://bp-3plgw:8187`(59 는 3plgw compose 가 `aimbase_default` 망에 별칭 `bp-3plgw` 로 동승(3plgw PR #60), 운영은 망 `aimbase-apps`. 3PLGW 컨테이너를 붙이는 일은 bp-infra CR-INFRA-008), `auth {type: API_KEY, in: header, name: X-Internal-Service-Key, value_env: TPLGW_INTERNAL_KEY}`, `readTimeoutMs 30000`. `github-bp-3plgw`: `baseUrl https://api.github.com`, `auth {type: BEARER, value_env: GITHUB_TOKEN_3PLGW}` |
| 운영 DB | 자격을 주지 않는다. 실행기에 PG 환경변수가 없고, 연결에 DB 도구를 노출하지 않는다 |
| 설치 | `scripts/chaws-bootstrap.sh` 대상 `3plgw`. 단계 admin(허용 테넌트에 `bp_3plgw`), tenant(테넌트, `agent-3plgw.env`, `secrets/bp-3plgw-api-key`), connections(CLI 연결 셋과 묶음), http(HTTP 연결 둘), workflow(`poomgo-change-repair`), smoke. 59 도 같은 스크립트로 세운다 |

### 4.6 한 번만 수리, 계정 로테이션, 한도 (BIZ-146, BIZ-147)

- 3PLGW 가 `change_id` 로 같은 변경을 한 번만 부른다. Aimbase 쪽은 `error_handling.retryMaxAttempts 0` 으로 repair 단계를 다시 돌리지 않고, 실패한 run 을 재실행하지 않는다. 실패는 `_callback` 으로 돌아가 사람 처리다
- 묶음이 ROUND_ROBIN 으로 계정을 고른다. 한도(`cli_account_limited:`)면 그 계정을 `resets_at` 까지 빼고 다음 계정으로 다시 실행한다(BIZ-128). 셋이 다 한도면 단계가 실패하고 run 이 failed, 회신 `error.reason` 에 그 메시지가 실린다
- 한도 응답이 실제로 `is_error=true` 로 오는지는 CR-161 2.3 이 미확인으로 남겼다. 이 CR 에서 한도 계정으로 실측하지 못하면 그대로 미확인으로 적는다

### 4.7 설정값 (코드에 박지 않는다)

| 값 | 자리 | 기본 |
|---|---|---|
| 승인 시한 | 워크플로우 정의 HUMAN_INPUT `timeoutMs` | 72시간(3PLGW 승인 시한 4시간보다 길게, 3PLGW 1-4) |
| 수리 단계 시한 | 워크플로우 정의 AGENT_CALL `timeout_ms` | 30분 |
| 회신 재시도 횟수, 간격 | `aimbase.workflow.callback.retry-max-attempts`, `retry-delay-ms` | 3, 5000 |
| 시한 감시 주기 | `aimbase.workflow.approval-timeout.sweep-ms` | 60000 |
| PR 라벨 | 입력 `pr_labels` | `["deploy-59"]` |
| 모델, 도구 모드, 계정 | 연결 config | claude-sonnet-4-5, HYBRID, work01 부터 work03 |
| 3PLGW 주소와 토큰, GitHub 토큰 | HTTP 연결 `baseUrl`, 서버 env 파일 | 없음 |

### 4.8 바꾸지 않는 것

- 승인 API, 실행 API, 실행 조회 API 의 모양
- HUMAN_INPUT 거절의 뜻(run 은 failed). 거절 뒤 계속 도는 길은 만들지 않는다
- 단계 `onFailure` 분기
- 다른 실행기의 환경과 계정 폴더
- 3PLGW 쪽 API(제안 제출, 계약 활성화, 표본 조회, 회신 수신)는 3PLGW 가 정한다. 이 CR 은 부르는 자리만 비워 둔다

## 5. 3PLGW 에서 받을 것 (컨타가 전한다)

| 순번 | 필요한 것 | 왜 |
|---|---|---|
| 1 | 「제안 제출」 API 경로, 인증 머리글, 본문. 검증 실패는 4xx 로 | submit 단계가 부른다. 검증과 등록, 사람 통보를 한 번에 하면 Aimbase 단계가 하나로 끝난다 |
| 2 | 「계약 활성화」 API 경로와 본문 | activate 단계가 부른다 |
| 3 | 회신 수신 API 경로(`_callback.path` 에 실어 보낸다) | 4.3.1 본문을 받는다 |
| 4 | 표본 조회 주소의 인증 방식 | repair 단계의 CLI 가 curl 로 받는다. 머리글 토큰이면 환경변수 이름을 정한다 |
| 5 | 3PLGW 안쪽 주소(망 `aimbase-apps`)와 59 주소 | HTTP 연결 `baseUrl` |

받음(2026-11-09 15:05, 3PLGW `docs/AI-SDLC/T3-2_API_설계.md` CR-3PLGW-094 2절): 1 `POST /api/v1/internal/poomgo-repair/changes/{change_id}/proposals`(201, 같은 run 재전송 200, 검증 실패 422), 2 `POST .../changes/{change_id}/activate`, 3 `POST .../changes/{change_id}/result`, 4 머리글 `X-Internal-Service-Key`, 실행기 환경변수 `TPLGW_INTERNAL_KEY`, 5 59 `http://59.8.160.12:8187`, 운영 `http://bp-3plgw:8187`. 3PLGW 가 바라는 것(1-4) 셋, 승인 시한 72시간, `last_good_url` 404 허용, 회신 `result.pr.title`, 은 전부 받았다. 3PLGW 1-1 의 `repo.url` 기본값 `bp-3plgw.git` 은 실제 레포 `therecommerce-develop/3plgw.git` 과 다르다(회신으로 알린다).

## 6. 영향 설계서

| 문서 | 바뀌는 것 |
|---|---|
| T1-1 | PRD-363 부터 PRD-367 |
| T1-3 | BIZ-143 부터 BIZ-147 |
| T1-5 | WorkflowRun `pending_approval` 에서 `failed`(시한 초과), PendingApproval `timeout` |
| T1-6 | 수신 「품고 변경 수리 요청」(외부), 발신 「실행 종료 회신」(외부), 「수리 제안 제출」(외부) |
| T1-7 | CR-174 작업 단위 |
| T2-1, T2-2 | 영향 없음(새 라이브러리 없음. 실행기 이미지에 OS 패키지 git, gh 가 늘지만 기술 스택 결정서는 패키지 목록을 두지 않는다. 구현 규약 변화 없음) |
| T3-1 | `pending_approvals.status` 값에 `timeout`. `action_log_id`, `policy_id` 외래키 제거(V76) |
| T3-2 | 실행 입력 `_callback`, 회신 본문, 단계 변수 `{{run.*}}`, 승인 API 를 소비앱 키로 부르는 길, HUMAN_INPUT `timeoutMs` |
| T3-3, T3-4 | 영향 없음(화면 없음. 승인 화면은 BO2 다) |
| T3-5 | TC-WF-034 부터 TC-WF-041 |
| CR-143 설계서 | 어댑터 출구 정규화 행: 첫 `{`~마지막 `}` 가 아니라 글 끝의 마지막 JSON 객체(4.3.5) |
| API 가이드 | 4-4 실행 입력 `_callback`, 4-6 상태(승인 시한 초과), 승인 API 소비앱 호출. v3.28.0 |
| 운용 가이드 | 2-13 「bp-3plgw 수리 실행기」. v3.25.0(origin/dev 의 CR-173 v3.23.x, v3.24.0 뒤 번호로 올림) |

## 7. 시험

- 단위시험 TC-WF-034 부터 TC-WF-041(T3-5)
- 로컬: api 를 로컬로 띄워 `_callback` 검사와 회신, 시한 감시, `{{run.id}}` 치환을 가짜 HTTP 수신기(로컬 포트)로 본다

### 7.1 로컬 확인 결과 (2026-11-09 15:40, 테넌트 cr160_local, 수신기 127.0.0.1:18174)

| 본 것 | 결과 |
|---|---|
| `_callback` 검사 | `path` 가 `/` 로 안 시작, 없는 `connection_id`, `connection_id` 빠짐, 객체 아님 네 경우 모두 400 `callback_invalid:<칸>`, run 생성 없음 |
| `{{run.id}}`, `{{run.workflow_id}}` | 첫 단계 본문에 run id, 워크플로우 id 가 들어가 수신기에 닿음 |
| 승인 | HUMAN_INPUT 에서 `pending_approval`, approve 뒤 다음 단계 실행, 회신 `status completed`, `error null`, `result` 는 `result_step` 단계 출력, 연결 `auth` 머리글 `X-Internal-Service-Key` 실림 |
| 거절 | 회신 `status failed`, `error {reason: "Human approval rejected", detail: "no"}` |
| 시한 초과(40초) | 감시기가 닫음. 회신 `status failed`, `error {reason: "approval_timeout", detail: "<timeout_at>"}`, 승인 대기 행 `timeout`. 늦은 approve 는 409 |
| 회신 재시도 | 첫 503 뒤 1초 만에 다시 보내 200 |
| V76 | 적용 전에는 네 run 모두 HUMAN_INPUT 에서 외래키 위반으로 `failed`. 적용 뒤 위 결과 |

59 와 3PLGW 를 잇는 확인은 컨타 신호 뒤에 한다.

### 7.2 59 설치 (2026-11-09 16:05)

- be 배포: api 재기동, `[ProdTenantMigration]` 10 테넌트 모두 v76(bp_3plgw 포함)
- `bootstrap admin 3plgw`, `tenant 3plgw`: 관리자 `platform-admin@aimbase.local`(비밀번호 59 `secrets/admin-password`), 테넌트 `bp_3plgw` 201, 실행기 전용 키 `.env` `BP_3PLGW_SERVER_MCP_API_KEY`, 소비앱 키 `secrets/bp-3plgw-api-key`
- agents 배포: 실행기 8개 재빌드(git 2.53, gh 2.103 포함), `aimbase-agent-3plgw` healthy
- `connections 3plgw`(ACCOUNTS work01 work02 work03), `http 3plgw`(TPLGW_BASE_URL http://59.8.160.12:8187, 19:40 에 http://bp-3plgw:8187 로 바꿈), `workflow 3plgw`, `smoke 3plgw`: 전부 201/200. smoke 에서 `GH_TOKEN 없음` 만 남음(사용자가 만드는 중)
- 3PLGW 컨테이너(bp-3plgw-api-1)에서 Aimbase `http://59.8.160.12:8280` 닿음(200). 소비앱 키로 워크플로우 조회 200(active true, 6 단계), 잘못된 `_callback` 은 400 `callback_invalid:path` 로 run 없이 거절. 맥에서는 59 의 8280 이 닿지 않는다(방화벽)
- 남은 것: GH_TOKEN 을 `.env.runner-3plgw`, `GITHUB_TOKEN_3PLGW` 를 `.env` 에 넣고 `./deploy.sh be`, `./deploy.sh agent-3plgw`(이때 계정 폴더를 공용으로 바꾼 compose 반영), 3PLGW 와 잇는 확인
- 59: 3PLGW 창이 가짜 품고에서 변경을 내면 3PLGW 가 웹훅을 부르고, 제안 또는 PR 이 나오고, BO2 승인이 들어오면 활성화 요청 또는 머지가 나가고, 회신이 3PLGW 에 닿는 것까지 본다. 거절과 시한 초과 회신도 본다
- 3PLcore `tools/testbook` 에 시험항목을 더한다(`/시험항목`)

### 7.3 59 토큰 없는 범위 시험 (2026-11-09 18:25, 테넌트 bp_3plgw, 컨타 결정 18:09)

3PLGW 가 59 에 아직 없어 compose 망(aimbase_default)에 대역 수신기 컨테이너(python, 18174)를 두고 HTTP 연결 `cb-probe-59` 로 받았다. 호스트 포트에 둔 수신기는 api 컨테이너에서 timeout 이었다(59 방화벽이 컨테이너에서 호스트 포트로 가는 길을 막음, Redis 6379 때와 같은 원인).

| 확인 | 결과 |
|---|---|
| 승인 대기 진입(V76) | 시험 워크플로우 run 3건 모두 `pending_approval` |
| 승인 거절 | run `failed` `{reason: Human approval rejected, detail: no}`, 회신 `/result/B` status failed 도달 |
| 승인 | `done` 단계 실행 뒤 `completed`, 회신 `/result/A` 도달 |
| 승인 시한 | 40초 시한, 감시가 09:20:28Z 에 닫음, run `failed` `{reason: approval_timeout, detail: <시한>}`, 회신 `/result/C` 09:20:58Z 도달 |
| 회신 머리글 | `X-Internal-Service-Key` 64자(연결 auth value_env 가 api env 에서 풀림) |
| 실제 웹훅 수신 | 소비앱 키 + `X-Tenant-Id` 로 run 생성 2건(38bf2f5b, 4ae9d6e6). `repair` 가 1초 만에 실패: CLI `Not logged in`. 실행기가 아직 전용(빈) 계정 폴더를 마운트하고 있어 예상된 실패. `./deploy.sh agent-3plgw`(공용 claude-accounts)로 풀린다 |
| 실패 회신 | 두 번째 run 의 `/result/real` status failed, error.step repair 도달. 첫 run 은 수신기가 닿지 않던 때라 재시도 뒤 36초 만에 `CALLBACK_FAILED` 이벤트 |
| 내부 키 | 수신기 기록을 읽다 키 값이 세션 기록에 찍혀 새 값으로 회전. 비밀 파일, 59 `.env`, `.env.runner-3plgw`, api 재기동(16초) 반영. 3PLGW 는 59 반영 전에 그 파일을 다시 읽어야 한다 |

남은 것: GH_TOKEN 도착 뒤 `./deploy.sh be`, `./deploy.sh agent-3plgw`, 3PLGW 59 반영 뒤 실제 연동(제안 전달, BO2 승인, 적용, 회신). 시험 흔적: 시험 워크플로우와 연결은 지웠고, 수신기 컨테이너 `cr174-receiver` 는 멈춤 상태로 남아 있다(지우기: `docker container rm cr174-receiver`). run 기록 5건은 bp_3plgw 테넌트에 남는다.

### 7.4 59 토큰 반영과 실행기 로그인 확인 (2026-11-09 23:47)

GitHub 토큰 파일이 23:44 에 와서(사용자 승인 ① 23:29) `GH_TOKEN` 을 `.env.runner-3plgw` 에, `GITHUB_TOKEN_3PLGW` 를 `.env` 에 넣고 `./deploy.sh be`, `./deploy.sh agent-3plgw` 를 돌렸다. 값은 비밀 파일에서 읽어 ssh 표준입력으로만 옮겼다.

| 확인 | 결과 |
|---|---|
| api, 실행기 | 둘 다 healthy. 실행기 마운트가 공용 `./claude-accounts` 로 바뀜(work01, work02, work03 에 자격 파일 있음) |
| gh | `gh auth status` 로그인 OK(GH_TOKEN). api env 에 `GITHUB_TOKEN_3PLGW` 있음 |
| smoke | 워크플로우, 연결 3개, 연결 그룹 200, 실행기 health ok, `TPLGW_INTERNAL_KEY` 있음 |
| 실행기 로그인 | AGENT_CALL 한 단계짜리 시험 워크플로우(연결 그룹 `cli-runner-bp-3plgw-rr`, HYBRID)가 4.6초 만에 `OK` 로 `completed`. 7.3 의 `Not logged in` 이 풀렸다. 시험 워크플로우는 지움 |
| 연결 시험 API | `POST /connections/{id}/test` 는 닿지 않는 `bp-3plgw` 에도 `ok, latencyMs 0` 을 돌려준다. 실제 호출을 하지 않으므로 로그인 확인 근거로 쓰지 않는다 |
| 작업 폴더 경고 | 실행기 기록의 `workingDirectory /data/workspace/bp_3plgw/runs/<run> is not a directory, using default cwd` 는 격리 NONE 이라 run 폴더를 만들지 않아서다. 수리 프롬프트는 절대 경로 `/data/workspace/bp-3plgw`(마운트 `./workspace-3plgw`, uid 999 소유)를 쓰므로 영향 없다 |
| CLI 한도 | 실행기 기록에 `rate_limit_event seven_day allowed_warning`. 공용 계정의 주간 한도가 가까워지고 있다 |

남은 것: 3plgw PR #60 반영 뒤 `bp-3plgw` 이름이 풀리면 컨타가 부르는 3PLGW 재시도(건 3)로 실제 연동(제안 전달, BO2 승인, 적용, 회신). 59 의 빈 전용 계정 폴더는 사용자가 지운다: `docker run --rm -v /home/therecommerce/aimbase:/w alpine:latest sh -c "cd /w && rmdir claude-accounts-3plgw/work01 claude-accounts-3plgw/work02 claude-accounts-3plgw/work03 claude-accounts-3plgw"`.

### 7.5 59 건 3 실패와 수정 (2026-11-10 00:04 컨타 승인 ①)

| 확인 | 결과 |
|---|---|
| 건 3 run be3907d1 | `repair` 는 completed 인데 `structured_data` 없음. `submit` 본문 `proposal` null, 3PLGW 400 `run_id 와 proposal 이 필요합니다`(3PLGW 동작은 맞다) |
| 원인 | 수리 단계에 `response_schema` 가 없어 CLI 응답이 글로만 남았고, 있었더라도 진행 설명의 중괄호 때문에 첫 `{`~마지막 `}` 추출이 JSON 이 아니었다(4.3.5) |
| 고침 | 워크플로우 정의에 `response_schema`, 출력 지시 문구 보강. 엔진 추출기는 마지막 JSON 객체. 단위 시험 11건 통과(새 3건 포함) |
| 59 반영 | 00:11 api 재생성(healthy), 00:12 워크플로우 재등록 200. 조회로 repair config 에 response_schema(필수 7칸)와 출력 지시 확인 |
| 재시도 run 10793e47(건 3, 00:13) | `repair` completed, `structured_data` 7칸(result_kind CONTRACT, 요약 "orderSheet.orderCode 를 orderSheet.orderNo 로"). `submit` 201(00:16, 33ms). run `pending_approval`(BO2 승인 단계). 승인은 사용자 합동 시험, 시한 04:16. 반영(activate)과 종료 회신은 감시 스크립트(`build/cr174_watch_run.sh`)로 지켜본다 |
| 승인 뒤(사용자 합동 시험 04:43) | approve 호출 04:43:23, ROUTER 가 CONTRACT 로 `activate`: POST `/changes/inv-20261109-e9d7aa3a/activate` 200(10ms), run completed(5 단계 결과). 종료 회신 POST `/changes/inv-20261109-e9d7aa3a/result` 200(6ms, 1회차, api 로그). 59 E2E 전 구간 통과 |

## 8. 진행 기록

| 일시 | 한 일 |
|---|---|
| 2026-11-09 14:02 | 착수. 브리핑, 공통 규칙, 결정 메모, T1 초안 원문 읽음. 3PLcore 브리핑 커밋 01a91176 |
| 2026-11-09 14:30 | 2절 실측, 설계 캐스케이드, 계약 문서 |
| 2026-11-09 15:20 | 엔진 커밋 37b671e(회신, 시한 감시, run 변수, 시험 10건 통과). 실행기 이미지(git, gh), compose 둘, deploy.sh, 설치 스크립트 대상 3plgw, 워크플로우 정의 JSON. 3PLGW 계약 반영(경로, 머리글, 72시간, pr.title, last_good 404) |
| 2026-11-09 15:45 | 로컬 확인 7.1 전부 통과. 승인 대기 외래키 결함을 찾아 V76 로 고침(4.3.4). 컨타에 GitHub 토큰 순서와 둘 자리 알림 |
| 2026-11-09 16:05 | 설치 스크립트 59 배치 분기(커밋 0e827c8). 59 설치 7.2 토큰 앞 단계까지 끝. 계정 폴더는 공용으로(컨타 회신 16:03) |
| 2026-11-09 17:50 | 컨타 질의 회신: 웹훅은 X-Tenant-Id 필수(59 실측 키만 400, 헤더 넣으면 200). 계약 문서 인증 줄을 "없어도 된다" 에서 "함께 보낸다" 로 고침 |
| 2026-11-09 18:28 | 컨타 결정(18:09)으로 59 토큰 없는 범위 시험 7.3: 승인 거절, 시한, 실패 회신 통과. 내부 키 회전, api 재기동. 실제 수리 단계는 계정 폴더 배포 전이라 Not logged in |
| 2026-11-09 19:42 | 컨타 알림(19:34)대로 3PLGW 연결 bp-3plgw baseUrl 을 59 도 http://bp-3plgw:8187 로(설치 스크립트 http 단계 재실행, 응답 200, 조회로 확인). 계약 문서, 운용 가이드, 스크립트 안내문 같은 값으로 |
| 2026-11-09 23:29 | 사용자 승인(컨타 전달) ①: GitHub 토큰 파일이 올 때까지 기다렸다 be, agent-3plgw 배포와 수리 단계 확인 뒤 완료. 토큰 도착은 컨타가 알린다 |
| 2026-11-09 23:50 | 토큰 반영 7.4: be, agent-3plgw 배포, 공용 계정 폴더 전환, gh 로그인, 실행기 AGENT_CALL 시험 OK(4.6초). 컨타에 알림, 3PLGW 재시도(건 3) 대기 |
| 2026-11-10 00:20 | 건 3(run be3907d1) 실패 원인: 수리 단계 response_schema 없음 + 진행 설명 중괄호로 첫 { 추출 실패(7.5). 컨타 승인 ①(00:04)로 워크플로우 정의에 response_schema, 엔진 추출기는 마지막 JSON 객체(4.3.5). 단위 시험 11건 통과. 59 api 재배포와 워크플로우 재등록으로 간다 |
| 2026-11-10 00:14 | 59 api 재배포(deploy.sh be), 워크플로우 재등록 200, 조회로 response_schema 확인(7.5). 컨타에 알림, 3PLGW 재시도(건 3) 대기 |
| 2026-11-10 00:24 | 3PLGW 재시도 run 10793e47: 수리 단계 structured_data 생성, 제안 전달 201, 승인 대기 진입(7.5). 컨타 승인 ①(00:21): 승인 뒤 반영과 회신 결과 알림 후 완료 보고, 운영 반영 전 완처리 없음 |
| 2026-11-10 04:25 | run 10793e47 은 04:21 현재 pending_approval 그대로(BO2 승인 미처리). 3PLGW 쪽 승인 시한 04:16 이 지났지만 Aimbase 승인 시한은 정의대로 72시간(11-12 00:16)이라 run 은 열려 있다. 감시 240분 창이 끝나 24시간으로 다시 걸었다. 컨타에 알림 |
| 2026-11-10 04:47 | 사용자 합동 시험 승인으로 run 10793e47 completed: activate 200, 종료 회신 200(7.5). 컨타에 알림, 완료 보고. 운영 반영은 별도 승인, 59 잔재(cr174-receiver 컨테이너, 빈 계정 폴더)는 사용자가 지운다(7.3, 7.4) |
| 2026-11-10 04:50 | 컨타 결정(04:48): 운영 반영 대기(완 처리 아님), dev 커밋 푸시(main 병합 아님), 운영 배포는 시험 뒤 사용자 승인과 시간대로 묶는다. 참고: 3PLGW 가 59 공용 내부 키를 원복하고 수리 창구(제안 전달, 종료 회신)만 전용 키로 분리한다. 그 사이 59 submit 이 403 일 수 있으나 Aimbase 연결 머리글과 값은 그대로 |
| 2026-11-10 05:00 | dev 푸시 준비(컨타 ② 04:52, 충돌 처리 ① 04:55): origin/dev 위 임시 작업 사본에 CR-174 커밋 22건만 cherry-pick. 설치 스크립트는 CR-173 구조(wms, wes-cs, widget 단계, 라벨, 태그 변수) 위에 3plgw 대상과 59 분리 배치 판별, 비밀값 파일 참조를 얹어 다시 만들고 wes-cs 줄은 그대로 둠. deploy.sh 와 compose 는 양쪽 추가, 운용 가이드 2-13 은 v3.25.0 으로 번호 재부여. 다른 CR 의 미푸시 커밋 12건은 손대지 않음(담당자에게 맡김) |
| 2026-11-10 05:05 | origin/dev 32724a6..0e6923f 로 fast-forward 푸시(CR-174 23건). 병합한 설치 스크립트를 59 build/ 에 올려 smoke 3plgw: 워크플로우, 커넥션 2건, 그룹 모두 200, 실행기 health ok, gh 로그인 유지, TPLGW_INTERNAL_KEY 있음, 종료 코드 0. 컨타에 커밋 범위와 결과 알림 |
| 2026-11-10 06:05 | 3PLGW 가 59 수리 창구를 전용 키로 분리(새 컨테이너 06:04). 실행기에서 Aimbase 키로 확인: 모르는 change_id 의 proposals, result 는 404(키 없이는 403), 실제 change_id 에 빈 본문은 400(run_id 와 proposal 필요). 제안 전달과 종료 회신 모두 키가 다시 통함. 그 사이(04:50~06:04) 는 키 있어도 403 이었음. Aimbase 쪽 변경 없음, 운영 반영 대기 |
