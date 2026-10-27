# CR-161: 워크플로우 단계의 연결 묶음과 bp 계열 CLI 계정 3개 나눠 쓰기

- 발번: 2026-10-27
- 원본 요구사항: `docs/origins/원본_요구사항_CR161_연결묶음_bp_CLI_계정_나눠쓰기_20261027.md`
- 요청: 사용자 10/26 23:21 ~ 10/27 00:31. bp 계열 실행기의 Claude CLI 사용을 therecommerce work01, work02, work03 세 계정에 정책으로 나누고, 한 계정이 한도에 걸리면 다른 계정이 받게 한다
- 결정:
  - 사용자 10/27 00:31 「1」(워크플로우 단계도 연결 묶음을 쓰게 하고 bp 계열 실행기를 세 계정 묶음으로 실행한다)
  - 범위 사용자 10/27 11:47 3절 ①(모든 경로가 묶음을 받고 bp-wes, bp-wms 는 묶음 id 를 보내게 각 레포에 CR 요청)
  - 사용자 10/28 05:25 「진행하세요」(비 bp 실행기에도 들어가는 실행기 공통 변경 4.4), 「컨셉만」(화면은 컨셉 승인 뒤 코드), 「요청하세요」(bp-wes, bp-wms 는 인계 문서로 요청)
- 규모: 중. 워크플로우 단계 설정에 묶음 칸, 워크플로우 스튜디오 단계 설정 화면에 칸 추가, 채팅 경로의 묶음 처리 변경. 새 테이블 없음
- 상태: 구현과 로컬 확인 끝(api 묶음 처리, 실행기 한도 표식, 단계 설정 화면 칸). 개발기 반영 전(7.6)

---

## 1. 문제

1. 개발기의 CLI 연결은 모두 계정 폴더 `local-agent-1` 하나를 쓴다(bp-wes-cs 는 그 로그인 파일을 복사한 `local-agent-cs`). 그 계정이 한도에 걸리면 모든 테넌트의 CLI 호출이 같이 멈춘다
2. 연결 묶음(CR-015)은 여러 연결을 전략(PRIORITY, ROUND_ROBIN, LEAST_USED)으로 고르고 실패 시 다음 연결로 넘어가는 기능이지만, bp 계열이 실제로 부르는 경로 중 묶음이 닿지 않는 곳이 있다(2.2)

## 2. 조사 결과

2026-10-27 00:40 개발기 master, 테넌트 DB 읽기 조회와 소스 기준이다.

### 2.1 bp 계열 연결과 부르는 경로

| 순번 | 업무 | 테넌트 | 연결 | 실행기 | 계정 폴더 | 부르는 경로 |
|---|---|---|---|---|---|---|
| 1 | bp-office 녹취 의심 구간 찾기, 과제와 결정 추출 | bp_office | `cli-runner-bp-office-001` | `cli-runner-bp-office` | `local-agent-1` | 워크플로우 2개의 LLM_CALL 단계가 `connection_id` 로 지정. 최근 3일 37회 |
| 2 | bp-wes 리포트, 사진 판독 | bp_wes | `cli-runner-bp-wes-001` | `cli-runner-bp-wes` | `local-agent-1` | bp-wes 서버가 `chat/completions` 에 `connection_id` 를 실어 부른다(`AimbaseClient`, `AimbaseVisionClient`) |
| 3 | bp-wes 슬랙 CS 조회 | bp_wes | `cli-runner-bp-wes-cs-001` | `cli-runner-bp-wes-cs` | `local-agent-cs` | bp-wes 서버가 `chat/completions` 에 `connection_id`, `stream=false` |
| 4 | bp-wms 현장 이슈 | bp_wes | `cli-runner-bp-wms-field-issue-001` | `cli-runner-bp-wes` | `local-agent-1` | bp-wms 서버가 `chat/completions` 에 `connection_id`, `stream=false`(`AimbaseChatClient`) |

- bp-wms 는 자기 테넌트와 실행기가 없고 bp_wes 테넌트의 연결로 bp-wes 실행기에서 돈다
- bp_wes 테넌트에는 워크플로우 단계가 연결을 지정한 곳이 없다. bp-wes, bp-wms 의 CLI 사용은 전부 채팅 경로다
- 9개 테넌트 모두 연결 묶음이 0건이다. 묶음 처리를 바꿔도 지금 동작이 바뀌는 테넌트는 없다

### 2.2 묶음이 닿는 곳과 닿지 않는 곳

| 순번 | 경로 | 지금 동작 | 근거 |
|---|---|---|---|
| 1 | 일반 채팅(도구 루프 없음) | 묶음을 주면 전략 순서로 시도하고 실패 시 다음 연결로 넘어간다. 다만 연결 id 로 정해지는 처리(채팅 실행 기록, 연결별 모델 설정)가 빠진다 | `OrchestratorEngine.java:243`, `:336`, `:373` |
| 2 | 도구 루프 채팅(`actions_enabled`) | 묶음을 보지 않는다. 묶음만 주면 어댑터 없이 도구 루프에 들어간다 | `OrchestratorEngine.java:293-310`, `:351` |
| 3 | 실시간 채팅 | 묶음에서 연결 하나를 고르기만 하고 실패 시 넘어가지 않는다 | `OrchestratorEngine.java:614` |
| 4 | 워크플로우 단계 | `connection_id` 만 읽는다. 묶음 칸이 없다 | `LlmCallStepExecutor.java:117`, `AgentCallStepExecutor.java:175`, `LargeInputStepExecutor.java:147` |
| 5 | AGENT_CALL 내부 | 서브에이전트가 도구 루프 채팅(2번)으로 돌고 묶음을 넘기지 않는다 | `SubagentRunner.java:346-360` |

### 2.3 한도에 걸린 응답

- CLI 가 `is_error=true` 인 result 를 내면 실행기가 예외로 올리고(`cli-runner` `ClaudeCliWorker.java:730`, 실시간은 `ClaudeCliRunnerClient.java:575`), 그 실행 단위의 CLI 프로세스를 닫는다(`RunnerService.java:165`). 그래서 묶음이 다음 연결로 넘어가면 그 연결의 계정 폴더로 새 프로세스가 뜬다
- 같은 실행 단위의 CLI 프로세스가 살아 있으면 계정 폴더가 다른 연결로 불러도 그 프로세스를 그대로 쓴다(`ClaudeCliWorkerPool.java:233`). 묶음이 단계마다 다른 연결을 고르면 기록상 연결과 실제 계정이 어긋난다
- 한도 응답이 실제로 `is_error=true` 로 오는지는 한도에 걸린 계정으로 확인해야 한다(미확인)

### 2.4 계정 폴더

- 개발기 `claude-accounts` 에는 `local-agent-1`, `test-acct-1` 만 있다. work01, work02, work03 폴더는 없다
- `claude-accounts-wes-cs` 는 bp-wes-cs 실행기 전용이다. 지난 대화 기록에 조회 계정 접속값과 조회 결과가 남아 폴더를 나눴다(`docker-compose.prod.yml` 주석)

## 3. 결정할 것

사용자의 「1」 은 채팅이 이미 묶음을 쓴다는 전제였다. 2.1 대로 bp-wes, bp-wms 는 `connection_id` 를 직접 실어 부르므로, 호출하는 쪽이 묶음을 부르지 않으면 세 계정에 나눠지지 않는다.

1. 묶음을 모든 경로에서 받고 부르는 쪽이 묶음을 부른다(권장)
   - aimbase: 묶음에서 연결을 고르는 일을 요청 입구 한 곳에서 하고, 고른 연결로 기존 단일 연결 처리를 그대로 실행한다. 한도와 실행기 끊김이면 다음 연결로 다시 실행한다. 일반, 도구 루프, 실시간 채팅과 워크플로우 단계(LLM_CALL, AGENT_CALL, LARGE_INPUT 등)에 같게 적용한다
   - bp-office: 워크플로우 2개의 단계 설정을 묶음으로 바꾼다(개발기 aimbase DB)
   - bp-wes, bp-wms: `connection_id` 대신 `connection_group_id` 를 보내도록 각 레포 워커에게 CR 로 요청한다
2. aimbase 안에서만 처리한다. CLI 연결 하나가 계정 폴더 세 개를 갖고 그 안에서 나눈다. 부르는 쪽은 바꾸지 않는다(잃는 것: 묶음과 겹치는 나눠 쓰기 장치가 하나 더 생기고 계정별 차단 상태가 묶음 화면에 보이지 않는다)
3. 이번에는 bp-office 워크플로우만 묶음으로 바꾼다(잃는 것: 하루 호출이 가장 많은 bp-wes-cs 와 bp-wes, bp-wms 가 한 계정에 남는다)

## 4. 설계

3절 ① 이다. 규칙은 BIZ-127, BIZ-128, 기능은 PRD-347, PRD-348, PRD-349, FE-041, API 는 T3-2 「Chat API 확장」, 화면은 T3-3, T3-4, 단위시험은 T3-5 TC-CG-101~123.

### 4.1 그룹 공통 실행

`ConnectionGroupSelector` 에 `execute(groupId, 고정 키, 커넥션 id 로 한 번 실행하는 함수)` 를 둔다. 묶음을 받는 모든 곳이 이것 하나를 부른다.

1. 후보 순서: 고정 키로 앞서 성공한 멤버가 아직 고를 수 있으면 먼저, 그다음 그룹 전략 순서. 서킷 브레이커가 열렸거나 한도로 빠진 멤버는 뺀다
2. 성공하면 서킷 브레이커 성공, 사용 횟수 1 증가, 고정 키를 그 멤버로 적는다
3. 실패는 BIZ-128 로 가른다
   - 계정 한도: 예외 사슬의 메시지 어디에든 `cli_account_limited:` 가 있으면. `resets_at` 까지 그 멤버의 서킷 브레이커를 연다(없으면 5분). 실시간 경로는 `cli_runner_unreachable:...:Runner stream error: cli_account_limited:...` 로 감싸여 오므로 머리만 보지 않는다
   - 실행기에 닿지 못함: `cli_agent_offline:`, `cli_routing_missing`, `cli_runner_unreachable:AGENT_OFFLINE:`, `cli_runner_unreachable:TURN_RELAY_DEAD:`. 서킷 브레이커 실패 1회
   - 그 밖: 그대로 던진다. 고정을 풀지 않는다. API 멤버(어댑터가 CLI 가 아님)는 CR-015 대로 모든 실패에서 넘긴다
4. 후보가 다 떨어지면 `IllegalStateException("connection_group_unavailable:retry_after=<가장 이른 시각>")`(비스트림 503)
5. 차례, 사용 횟수, 서킷 브레이커, 고정은 테넌트 id 를 붙인 키로 센다
6. 고정은 메모리 맵(마지막 사용 시각 포함)에 두고 세션 TTL(24시간, BIZ-002)이 지난 것은 주기적으로 지운다. 새 저장소는 쓰지 않는다
7. `GenericCircuitBreaker` 에 지정 시각까지 여는 메서드를 더한다. 기존 `executeWithGroup` 은 `execute` 위로 다시 만든다

### 4.2 채팅

1. `OrchestratorEngine.chat`: 그룹이 있으면 맨 앞에서 `execute(groupId, session_id, connId -> chat(request.withConnection(connId)))`. `withConnection` 은 connection_id 를 넣고 connection_group_id 를 지운다. 그래서 채팅 실행 기록, 커넥션별 모델 설정, 도구 루프가 단일 커넥션과 같아진다. 지금의 그룹 분기(`:293-310`, `:373`)는 없앤다
2. `chatStream`: 같은 감싸기. 감싼 sink 가 text, thinking, tool_use 이벤트를 하나라도 내보냈으면 넘기지 않고 그대로 던진다. 지금의 고르기만 하는 분기(`:614`)는 없앤다
3. session_id 가 없는 요청은 고정 없이 전략대로 고른다. 실패한 시도는 세션에 아무것도 남기지 않는다(세션 저장은 성공 뒤)

### 4.3 워크플로우 단계

1. LLM_CALL: config `connection_group_id` 가 있으면 `execute(groupId, workflowRunId, connId -> 그 커넥션으로 단계 실행)`. 실행기 CLI 프로세스가 run 단위로 이어지므로(`LlmCallStepExecutor.java:650-663`) run 고정이어야 기록과 실제 계정이 맞는다. EVALUATOR_LOOP generator 는 같은 실행기를 써서 같이 된다
2. AGENT_CALL: 단계 config 를 서브에이전트 요청에 넘기고, `SubagentRunner` 가 `connection_group_id` 를 자식 채팅 요청에 싣는다. 4.2 의 감싸기가 자식 세션을 고정 키로 쓰므로 CR-106 이어하기도 같은 계정에서 돈다
3. LARGE_INPUT: 청크, Reduce 호출마다 `execute(groupId, workflowRunId, ...)`. 고정 덕에 평소에는 한 멤버로 돌고, 한 청크가 한도에 걸리면 그 청크부터 다음 멤버로 간다. 청크 이벤트와 실행기 정리 등록은 그 호출을 실제로 한 멤버로 한다
4. 강제 취소(CR-116, CR-121)의 실행기 정리 등록(`SubagentRunner.java:131`, `LargeInputStepExecutor.java:374`)은 실제로 실행한 멤버 커넥션으로 한다. AGENT_CALL 은 자식 채팅이 멤버를 고르므로, 그룹이면 멤버 커넥션 전부로 등록한다(없는 세션 정리는 실행기가 무시한다)

### 4.4 실행기

1. `ClaudeCliWorker.awaitResult`: rate_limit_event 의 `rate_limit_info.status=rejected` 를 받으면 한도 상태와 `resetsAt`, `rateLimitType` 을 적는다. 그 뒤 assistant 글자는 잡아 둔다
   - result 가 is_error 면 `ClaudeCliException("cli_account_limited:resets_at=<초>:type=<종류>:" + 원문)`
   - result 가 성공이면 잡아 둔 글자를 순서대로 내보낸다
2. `RunnerController /v1/chat`: CLI 실패를 `{"error": 메시지}` 본문으로 돌려준다. BE 의 `ClaudeCliRunnerClient` 는 이미 본문을 메시지에 싣는다(`Runner /v1/chat 실패: <본문>`)
3. `ClaudeCliWorkerPool.getOrCreateMain`: 살아 있는 main 프로세스의 계정 폴더가 요청과 다르면 닫고 새로 띄운다. 그룹이 멤버를 바꿨는데 이전 계정 프로세스를 그대로 쓰는 일(2.3)을 막는다. 그룹을 쓰지 않는 지금 요청은 계정 폴더가 늘 같아 동작이 바뀌지 않는다
4. `cli_account_limited:` 머리는 cli-runner 와 platform-core 에 상수로 하나씩 두고 서로를 주석으로 가리킨다(platform-core 가 cli-runner 를 의존하지 않는다)

### 4.5 화면

워크플로우 스튜디오 ConfigPanel 의 LLM_CALL, AGENT_CALL, LARGE_INPUT 에 커넥션 그룹 칸(T3-3). 화면 코드 전에 화면 컨셉 승인을 받는다.

화면 컨셉(사용자 10/28 05:45 「1」 승인):

1. 자리: 세 단계 설정의 「LLM 연결」 칸 바로 위에 「커넥션 그룹」 칸. 이름은 메뉴 「Connection Groups」 화면의 부제 「커넥션 그룹」과 맞춘다
2. 고르는 목록: 맨 위 「사용 안 함」, 그 아래 켜져 있는 그룹마다 `이름 (전략)`, 예 `bp-office 계정 나눠 쓰기 (Round Robin)`. 꺼진 그룹은 보이지 않지만 저장된 값이 꺼진 그룹이면 `이름 (꺼짐)` 으로 남겨 보인다
3. 그룹을 고르면 「LLM 연결」 칸을 비우고, 고르는 칸 대신 「커넥션 그룹이 연결을 고릅니다」 를 보인다. 「사용 안 함」으로 되돌리면 다시 고를 수 있다(값은 빈 채). 저장할 때 그룹이 있으면 `connection_id` 를 빼고, 그룹이 없으면 `connection_group_id` 를 뺀다
4. 그룹 목록을 못 불러오면 칸 아래 「커넥션 그룹을 불러오지 못했습니다」, 저장된 값은 그대로 두고 지우지 않는다
5. 드롭다운은 디자인 시스템 Select(같은 패널의 스킬 칸 `SkillPinsField` 와 같은 것). 칸은 단계 설정 공통 렌더러가 아니라 스킬 칸처럼 별도 컴포넌트로 둔다

### 4.6 남는 한계

1. api 를 다시 띄우면 고정이 없어진다. 다음 요청은 전략대로 고르고, 4.4-3 으로 실행기 프로세스도 그 계정으로 바뀐다
2. 한도로 뺀 멤버 기록도 메모리라 api 를 다시 띄우면 없어진다. 다시 고르면 한 번 실패하고 다음 멤버로 넘어간다

## 5. 개발기 반영

개발기 DB 쓰기, 원격 재기동, 실행기 다시 띄우기 직전에 컨타에 `[승인요청]` 을 올린다. 계정 로그인은 사용자가 브라우저로 한다. 키 값과 로그인 정보는 기록하지 않는다.

1. api: 작업 폴더 사본으로 `./deploy.sh be`
2. 실행기: bp-office, bp-wes, bp-wes-cs 실행기만 새 이미지로 다시 띄운다(4.4). 비 bp 실행기는 건드리지 않는다
3. 계정 폴더: `claude-accounts/work01`, `work02`, `work03`(bp-office, bp-wes 실행기가 같이 쓴다), bp-wes-cs 는 `claude-accounts-wes-cs/` 아래 같은 이름으로 따로 로그인한다(2.4 의 분리 이유 유지)
4. 커넥션: 지금 `-001` 커넥션의 config 를 그대로 복사하고 `config_dir` 만 계정 폴더로 바꾼 멤버를 계정마다 만든다. 이름은 `-001` 자리에 `-work01` 처럼 계정 이름(CR-162 와 같은 짓기). `-001` 은 되돌리기용으로 남긴다
5. 그룹(ROUND_ROBIN): bp_office `cli-runner-bp-office-rr`, bp_wes `cli-runner-bp-wes-rr`, `cli-runner-bp-wes-cs-rr`, `cli-runner-bp-wms-field-issue-rr`. 처음에는 work01, work02 두 멤버, work03 은 사용자가 깨어났다고 알리면 더한다
6. bp-office 워크플로우 2개의 LLM_CALL 단계 `connection_id` 를 `connection_group_id` 로 바꾼다
7. bp-wes, bp-wms 는 6절 CR 이 반영되면 그룹으로 바뀐다

## 6. 다른 레포 요청

| 순번 | 레포 | 바꿀 곳 | 내용 |
|---|---|---|---|
| 1 | bp-wes | `AimbaseClient`, `AimbaseVisionClient` 의 CLI 판독(`CLAUDE_CLI`), 슬랙 CS 분류와 조회 호출 | `chat/completions` 에 `connection_id` 대신 `connection_group_id`. 값은 설정에서 읽는다. qwen 판독 모델은 그대로 |
| 2 | bp-wms | `AimbaseChatClient` | 같음 |

계약은 aimbase API 가이드 § 25 「연결 묶음」(v3.23.0). 응답 형식은 바뀌지 않고, 모든 멤버가 막히면 503 `connection_group_unavailable` 이 새로 생긴다.

두 레포는 이 건의 컨타 팀 밖이라, 받는 쪽 컨타에게 전달할 인계서를 따로 썼다: `docs/기록/CR-161_bp-wes_bp-wms_인계.md`(사용자 승인 10/28 05:25).

## 7. 구현과 로컬 확인

### 7.1 api 구현 (10/27)

| 순번 | 곳 | 바뀐 것 |
|---|---|---|
| 1 | `ConnectionGroupSelector` | 그룹 공통 실행. 고정 멤버 먼저, 넘길 실패 분류(계정 한도, 실행기에 닿지 못함, 그 밖), 한도는 풀리는 시각까지 서킷 브레이커를 연다, 모두 빠지면 `connection_group_unavailable:retry_after=`. 차례, 고정, 서킷 브레이커를 테넌트별로 나눴다. 고정 만료 청소를 후보 계산 앞으로 옮겼다(뒤에 있으면 만료된 고정이 한 번 더 쓰였다) |
| 2 | `GenericCircuitBreaker` | `openUntil(시각)` |
| 3 | `OrchestratorEngine` | `chat`, `chatStream` 이 그룹이면 멤버를 골라 단일 커넥션 처리로 실행. 실시간은 이벤트를 보낸 뒤 넘기지 않는다. 실시간 run 이 예외로 끝나면 FAILED 로 닫는다(전에는 완료로 남아, 넘긴 뒤 실패한 멤버의 run 이 완료로 보였다) |
| 4 | `LlmCallStepExecutor`, `SubagentRunner`, `LargeInputStepExecutor` | 단계 config `connection_group_id`. LLM_CALL 과 LARGE_INPUT 은 run 고정, AGENT_CALL 은 자식 세션 고정. AGENT_CALL 의 강제 취소 등록과 실행기 정리는 멤버 전부. LARGE_INPUT 은 조각 호출마다 그룹을 거친다 |
| 5 | 가이드 | API 가이드 v3.23.0 § 25, 운용 가이드 v3.16.0 시나리오 P |
| 6 | `ClaudeCliWorker` (10/28) | rate_limit_event `status=rejected` 를 받으면 그 뒤 글자를 잡아 둔다. result 가 is_error 면 `cli_account_limited:resets_at=<초>:type=<종류>:` 머리를 단 실패, 성공이면 잡아 둔 글자를 차례대로 내보낸다 |
| 7 | `ClaudeCliWorkerPool` (10/28) | 살아 있는 main 프로세스의 계정 폴더가 요청과 다르면 닫고 자리를 돌려준 뒤 새로 띄운다 |
| 8 | `RunnerController` (10/28) | `/v1/chat` 의 CLI 실패를 500 `{"error": 메시지}` 본문으로 |
| 9 | `CliAccountLimitedException`, `ClaudeCliAdapter`, `GlobalExceptionHandler` (10/28) | 실행기 실패 메시지 사슬에서 한도 머리를 찾으면 `cli_runner_unreachable` 로 감싸지 않고 한도 예외로 올린다. 단일 커넥션 요청은 503, 본문 error 는 머리만(한도 원문은 싣지 않는다) |

### 7.2 단위 시험

TC-CG-101~110 `ConnectionGroupSelectorTest` 11건, TC-CG-115 `LlmCallStepExecutorGroupTest` 2건, TC-CG-116 `SubagentRunnerGroupTest` 1건, TC-CG-117 `LargeInputStepExecutorGroupTest` 1건 통과. 바뀐 클래스를 쓰는 기존 시험 14개 클래스도 실패 0. TC-CG-111~114 는 엔진 단위 시험 틀이 없어 7.3 로컬 실제 호출로 봤다.

10/28: TC-CG-118~120, 122 `ClaudeCliAccountLimitTest` 4건(CLI 대역 스크립트, 상한 1 자리 풀), TC-CG-121 `RunnerControllerCliFailureTest` 1건, TC-CG-123 `CliAccountLimitedTest` 5건(일반, 실시간, 다른 실패, 엔진이 감싼 한도의 503, 사슬 안쪽 머리) 통과.

### 7.3 로컬 실제 호출 (10/27 12:35 ~ 12:44)

맥 로컬 서버(8181)와 로컬 실행기(17147, 연결 `cli-cr159-local`), 로컬 테넌트 `cr159_local`. 시험 연결 `cli-cr161-ghost` 는 `cli-cr159-local` 의 config 를 복사하고 실행기 이름만 없는 이름으로 바꿨다. 묶음 `grp-cr161-local`(ROUND_ROBIN, ghost, cr159-local).

| 순번 | 확인 | 결과 |
|---|---|---|
| 1 | 일반 채팅, 차례가 ghost | ghost `cli_agent_offline` 로 넘김, cr159-local 이 답 |
| 2 | 일반 채팅, 차례가 cr159-local | 첫 시도에 답 |
| 3 | 실시간 채팅, 차례가 ghost | 넘긴 뒤 글자와 done 만 왔고 오류 이벤트 없음 |
| 4 | 같은 세션 다시, 차례가 ghost | 고정된 cr159-local 이 첫 시도. 앞 대화를 이어 받았다(2 다음 3) |
| 5 | 도구 사용 채팅(`actions_enabled`) | 묶음으로 답. 도구 분기는 묶음 처리 안쪽이라 같은 처리 |
| 6 | `connection_id`=ghost 와 묶음을 같이 줌 | 묶음이 앞서 cr159-local 로 답 |
| 7 | 두 단계 워크플로우 2회 | 첫 단계에서 ghost 실패 뒤 cr159-local, 둘째 단계도 cr159-local. 단계 이벤트 `connection_id` 가 실제 멤버 |
| 8 | 채팅 실행 기록 | 시도마다 run 하나, 실제 멤버. 3 의 ghost 시도가 완료로 남은 것을 고쳐 다시 보니 FAILED |

계정 한도로 넘기는 것은 7.4 에서 봤다.

### 7.4 로컬 계정 한도 (10/28 05:41 ~ 05:43)

7.3 과 같은 로컬 서버와 실행기. 실행기의 claude 를 대역 스크립트로 바꿔, 계정 폴더가 `limited` 로 끝나면 실제 한도 응답과 같은 모양(rate_limit_event rejected, 한도 문구, is_error result)을 내고 아니면 진짜 claude 를 부른다. 시험 연결 `cli-cr161-limited`, `cli-cr161-limited2` 는 계정 폴더만 `limited`. 묶음 `grp-cr161-limit`(PRIORITY, limited 1순위, cr159-local 2순위), `grp-cr161-limit2`(같은 모양, limited2).

| 순번 | 확인 | 결과 |
|---|---|---|
| 1 | 단일 커넥션 limited, 일반 채팅 | 503 `cli_account_limited:resets_at=<한 시간 뒤>:type=five_hour:`. 한도 원문은 본문에 없다 |
| 2 | 묶음 일반 채팅 | limited `ACCOUNT_LIMITED` 로 넘김, cr159-local 이 답. 서킷 브레이커가 resets_at 시각까지 열림(로그 시각과 일치) |
| 3 | 묶음 일반 채팅, 새 세션 | limited 는 풀리는 시각까지 건너뛰고 cr159-local 이 첫 시도에 답 |
| 4 | 묶음 실시간 채팅(limited2) | limited2 에서 넘긴 뒤 글자와 done 만 왔고 한도 문구가 새지 않았다 |
| 5 | 단일 커넥션 limited2, 실시간 | 글자를 보내기 전 실패라 503 같은 본문 |
| 6 | 실행 기록 | 1, 5 는 FAILED 하나, 2, 4 는 FAILED(limited) 다음 COMPLETED(cr159-local), 3 은 COMPLETED 하나 |

### 7.5 화면 (10/28 05:46 ~ 06:05)

| 순번 | 곳 | 바뀐 것 |
|---|---|---|
| 1 | `ConnectionGroupField`(새), `ConfigPanel` | 4.5 컨셉대로. LLM_CALL 은 기본 탭 |
| 2 | `components/connectionGroups/strategies.ts`(새), `ConnectionGroups` 화면 | 전략 이름 목록을 두 화면이 같이 쓴다 |
| 3 | `ConnectionGroupEntity` | 목록, 생성, 수정 응답이 `isActive`, `isDefault` 로 와서 화면 타입(`is_active`)과 상세 응답과 어긋났다. 새 칸에 그룹이 하나도 안 보이고, 그룹 화면의 Default 표시도 지금까지 안 보였다. 엔티티 필드에 상세와 같은 이름을 붙였다(사용자 10/28 05:54 「1」). TC-CG-124. API 가이드 § 25 에 적었다 |

로컬 확인: 7.4 와 같은 로컬 서버, 화면 개발 서버(3181, 레포 설정을 바꾸지 않고 확인용 설정 파일로 로컬 서버를 가리켰다), 헤드리스 브라우저. 시험 워크플로우 `wf-cr161-screen`(LLM_CALL 그룹, AGENT_CALL 그룹, LARGE_INPUT 단일 연결).

| 순번 | 확인 | 결과 |
|---|---|---|
| 1 | 세 단계를 차례로 연다 | 그룹이 있는 두 단계는 `이름 (전략)` 과 「커넥션 그룹이 연결을 고릅니다」, LARGE_INPUT 은 「사용 안 함」과 연결 고르는 칸 |
| 2 | 목록 | 사용 안 함, 켜진 그룹 셋 |
| 3 | LARGE_INPUT 에서 그룹을 고르고 저장 | config 에 `connection_group_id`, `connection_id` 없음 |
| 4 | LLM_CALL 에서 사용 안 함으로 되돌리고 저장 | 연결 고르는 칸이 돌아오고, config 에 두 값 모두 없음(자동 라우팅) |
| 5 | 그룹 목록 요청을 500 으로 막고 연다 | 칸이 잠기고 저장된 값 `grp-cr161-limit (불러오지 못함)`, 아래 「커넥션 그룹을 불러오지 못했습니다」 |

### 7.6 남은 일

1. bp-wes, bp-wms 는 인계 문서(`docs/기록/CR-161_bp-wes_bp-wms_인계.md`)로 요청. aimbase 개발기 반영 뒤 묶음 id 를 넣게 알린다
2. 개발기 반영(5절), 계정 로그인은 사용자
