# CR-189: 서버 도구 통로 테넌트 누락으로 cron_list 실패

- 발번: 2026-11-14
- 원본 요구사항: `docs/origins/원본_요구사항_CR189_서버_도구_통로_테넌트_누락_20261114.md`
- 요청: 컨타 aimbase-56899 브리핑. 사용자 승인 2026-11-14 01:00 /전승 「CR-185 cron_list 결함 별건 CR 발번해 고침」. 실행맵 `docs/기록/실행맵_채팅_위젯_고도화.md` 14번 행(갈래 추가)
- 고칠 방향: 2026-11-14 컨타 [승인] 1번(권장). 같은 통로의 테넌트와 대화 세션 누락 전부, cron_delete 는 자기 테넌트 것만, remote_trigger 는 테넌트 되돌리기
- 앞 건: CR-072(서버 도구 통로), CR-117(작업장 머리글), CR-147(스킬 지정 머리글), CR-183(data 통로에 세션 싣기), CR-185(대화 세션 머리글과 승인 확인)
- 규모: 소. 새 API, 화면, 테이블 없음
- 상태: 개발기 반영(운영 반영 전)

---

## 1. 배경

- CR-185 가 2026-11-13 개발기 59 에서 위젯 대화(CLI 실행기)로 승인 카드를 확인하다가, 승인 뒤 `cron_list` 가 「테넌트 컨텍스트가 필요합니다」로 실패하는 것을 찾았다. 이번 위젯 CR 들 전부터 있던 결함이다
- 승인 카드는 떴다. 같은 요청 안에서 승인 확인은 테넌트를 보고 정책을 찾았다. 도구만 테넌트를 받지 못했다

## 2. 원인

1. CLI 실행기는 서버 도구를 `/mcp` 로 부른다. 키 인증이 요청 스레드에 키의 테넌트를 채운다(`ApiKeyAuthenticationFilter.java:110`). 도구도 그 요청 스레드에서 돈다(`ServerMcpConfig.java:131`, immediateExecution)
2. 그런데 통로가 도구에 넘기는 실행 맥락은 테넌트와 세션을 비워 만들었다(`ServerMcpToolDispatcher.java:290, 301`, `ToolContext.minimal(null, null)`). CR-072 때 둔 모양이 그대로 남았다
3. API 연결 대화는 같은 맥락에 테넌트, 대화 세션, 사용자를 채운다(`OrchestratorEngine.java:289, 702`). 같은 도구가 연결 종류에 따라 되고 안 된다
4. 실행기는 대화 세션을 이미 싣는다(`X-Aimbase-Session-Id`, `ClaudeCliWorker.java:1289`, CR-185). 통로는 이 값을 data 통로(CR-183)와 승인 확인에만 쓰고 도구 맥락에는 넣지 않았다

## 3. 전수

노출 목록 기본값(`McpExposurePolicy.DEFAULT_FALLBACK`, 45개)과 data 통로 2개의 도구 소스에서 맥락의 테넌트와 세션을 읽는 곳을 모두 찾았다.

| 순번 | 무리 | 도구 | 고치기 전 |
|---|---|---|---|
| 1 | 테넌트가 없어 바로 실패 | cron_list, schedule_cron, remote_trigger | 「테넌트 컨텍스트가 필요합니다」 |
| 2 | 테넌트를 보지 않음 | cron_delete | 예약 번호만 맞으면 다른 테넌트 예약도 지운다. 예약 표는 마스터에 모든 테넌트 것이 함께 있다. 관리 API `DELETE /api/v1/scheduled-jobs/{id}`, `PATCH .../toggle` 도 같은 함수를 써 같다 |
| 3 | 작업장 머리글이 없을 때 테넌트 폴더 대신 default 폴더 | bash, file_write, download_file, builtin_* 9개 | 실행기는 작업장 머리글을 실어 영향 없다. 머리글 없이 직접 붙은 MCP 클라이언트만 해당 |
| 4 | 대화 세션이 없어 실패하거나 엉뚱하게 동작 | read_tool_result, brief(실패), task_create, task_list, task_stop, todo_write, send_message, tool_search(활성화 안 됨), builtin_safe_edit(편집 이력 세션 없음) | 실행기 대화에서도 같다 |
| 5 | 테넌트 표시를 지움 | remote_trigger | 끝날 때 요청 스레드의 테넌트를 지워(clear) 같은 요청의 뒤 훅과 같은 턴의 다음 도구가 테넌트를 잃는다 |
| 6 | 영향 없음 | 나머지(web_search, http_request, calculate 등 맥락을 보지 않는 도구), 외부 MCP 와 원격 에이전트 도구, data 통로 2개 | data 통로는 CR-183 이 이미 테넌트와 세션을 싣는다 |

## 4. 고치는 것

1. 통로 맥락(`ServerMcpToolDispatcher.buildToolContext`): 테넌트는 `TenantContext.getTenantId()`, 대화 세션은 `X-Aimbase-Session-Id`(`RequestContext.getChatSessionId()`)로 채운다. data 통로와 같은 한 모양으로 합친다
   - 워크플로우 단계는 세션 머리글이 없어 세션은 비운 채 둔다
   - 권한 수준(READ_ONLY), 승인 상태, 작업장, 스킬 지정은 지금 그대로다
   - 작업장 머리글이 없으면 파일 도구는 default 폴더 대신 그 테넌트 폴더를 쓴다. API 연결과 같아진다
2. 예약 지우기와 켜고 끄기(`CronScheduleManager.deleteJob`, `toggleActive`): 그 테넌트 예약만 다룬다. 테넌트가 없거나 다른 테넌트 예약이면 「존재하지 않는 스케줄」로 같은 답을 준다. 남의 예약 번호가 있는지 알리지 않는다. cron_delete 도구는 테넌트가 없으면 다른 cron 도구와 같이 거절한다
3. remote_trigger: 끝날 때 테넌트를 지우지 않고 들어올 때 값으로 되돌린다

바뀌지 않는 것: 실행기, 위젯, 화면, API 모양, 테이블, 런타임 설정.

## 5. 시험

### 5.1 단위시험

| 순번 | 시험 | 확인 |
|---|---|---|
| 1 | `ServerMcpToolDispatcherTest.cr189_tenant_and_chat_session_propagated_into_toolcontext` | 키의 테넌트와 대화 세션이 도구 맥락에 실린다 |
| 2 | `ServerMcpToolDispatcherTest.cr189_without_session_header_keeps_tenant_and_null_session` | 세션 머리글이 없으면 테넌트만 실리고 세션은 비운다 |
| 3 | `CronScheduleManagerTenantTest` 4건 | 자기 테넌트 예약만 지우고 켜고 끈다. 다른 테넌트, 테넌트 없음은 거절 |
| 4 | `RemoteTriggerToolTest.caller_tenant_is_restored_after_trigger` | 트리거 뒤에도 부른 쪽 테넌트가 남는다 |

`ServerMcpToolDispatcherTest` 기존 시험 포함 27건 통과(2026-11-14).

### 5.2 로컬 확인

2026-11-14 01:18, 작업 폴더 bootRun(18189), 테넌트 cr160_local 과 cr178_local 에 시험 키를 하나씩 넣고 `/mcp` 를 실행기와 같은 머리글(`Authorization: ApiKey`, `X-Aimbase-Session-Id`)로 불렀다. 시험 키, 예약, 할 일은 끝난 뒤 지웠다.

| 순번 | 부른 것 | 결과 |
|---|---|---|
| 1 | cr160_local cron_list | 통과. 0개 |
| 2 | cr160_local schedule_cron | 통과. 예약 `sj-129e5380` 등록 |
| 3 | cr160_local cron_list | 통과. 1개 |
| 4 | cr178_local cron_list | 통과. 0개(남의 예약이 보이지 않는다) |
| 5 | cr178_local cron_delete `sj-129e5380` | 통과. 「존재하지 않는 스케줄」, 예약은 남는다 |
| 6 | cr160_local task_list, todo_write(세션 있음) | 통과. 세션으로 조회, 저장된다 |
| 7 | cr160_local cron_list(세션 머리글 없음, 워크플로우 단계 모양) | 통과. 1개 |
| 8 | cr160_local cron_delete `sj-129e5380` | 통과. 지워짐, 다시 cron_list 0개 |

관련 단위시험(`com.platform.mcp.*`, `com.platform.tool.*`, `com.platform.workflow.*`, `com.platform.orchestrator.approval.*`) 641건 통과.

### 5.3 개발기 59 확인

2026-11-14 01:35 `./deploy.sh be` 로 api 반영(e256d3cc, CR-187 bd73fb30 위). 반영 전 59 jar 와 항목별 검사합을 대조해 다른 것이 CR-189 클래스 5개뿐인 것을 확인했다. workmap 테넌트에 시험 키를 잠깐 넣고 `/mcp` 를 실행기와 같은 머리글로 불렀다. 시험 키, 예약, 할 일은 끝난 뒤 지웠다.

| 순번 | 부른 것 | 결과 |
|---|---|---|
| 1 | cron_list(세션 있음) | 통과. 반영 직후 첫 호출은 서버 도구가 통로에 올라오기 전(01:36:23 노출)이라 빈 응답, 노출 뒤 다시 불러 통과 |
| 2 | schedule_cron | 통과. 예약 `sj-8c6d66b6` 등록 |
| 3 | cron_list(세션 머리글 없음) | 통과. 1개 |
| 4 | task_list, todo_write(세션 있음) | 통과 |
| 5 | cron_delete `sj-8c6d66b6` | 통과. 지워짐 |

남은 것: 운영 반영(따로 승인).

## 6. 영향 설계서

- 규모 소라 설계 캐스케이드는 돌리지 않았다. T3-13(CR-072 서버 MCP 설계서)의 도구 호출 맥락 설명은 이 문서가 보정한다
