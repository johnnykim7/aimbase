# CR-187 59 CS 실행기 작업 폴더를 api 와 맞추기

> 설계 버전: 0.1 | 최종 수정: 2026-11-14 | 관련 CR: CR-181, CR-182, CR-173, CR-174

- 원천: 2026-11-13 19:41 사용자 /전승 「후속 CR 발번」. CR-181 완료 보고 「59 CS 실행기의 작업 폴더가 api 와 달라(workspace-wes-cs) 실행기가 직접 만든 파일이 대화에 올라오지 않음」
- 실행맵: [기록/실행맵_채팅_위젯_고도화.md](기록/실행맵_채팅_위젯_고도화.md) 12번 행(추가 갈래)
- 번호 대역: PRD-600 ~ 609, BIZ-260 ~ 264, FE-150 ~ 154, TC 130 ~ 134, 시험 영역 20, 마이그레이션 V90(쓰지 않음)
- 상태: 착수. 규모와 고칠 방향 승인 대기

## 1. 범위

| 순번 | 넣는 것 | 바뀌는 곳 |
|---|---|---|
| 1 | 대화 작업 폴더를 그 연결이 가는 실행기가 볼 수 있는 폴더에 잡는다. 따로 떨어진 실행기는 실행기 이름별 작업 폴더 표(환경설정)로 정한다 | 서버 |
| 2 | 그 표의 폴더는 서버 허용 폴더에 저절로 든다 | 서버 |
| 3 | 59 와 운영 chaws 의 CS 실행기 작업 폴더를 api 와 같은 경로로 맞추고, api 도 그 폴더를 같은 경로로 붙인다 | compose |

넣지 않는 것

- 품고 수리 실행기(bp-3plgw)의 워크플로우 run 폴더 어긋남. 같은 뿌리지만 수리 프롬프트가 실행기 안 절대 경로를 써서 영향이 없다(CR-174 287행). 대화 파일 카드가 걸리지 않는다.
- CS 실행기가 다른 실행기의 작업 폴더를 보게 하는 것. CR-173 이 나눈 이유(그 폴더의 지난 대화와 산출물에 claude_ro 접속값과 위젯 조회 결과가 남아 있다)가 그대로다.

## 2. 지금 상태 (실측)

### 2.1 원인

서버는 실행기 연결의 대화마다 작업 폴더를 `{base}/chat/{세션}` 에 잡고 실행기에 그 경로를 작업 폴더로 넘긴다(OrchestratorEngine.java:1148, ChatWorkspaceAssigner.java:36). 이 설계는 「base 는 BE 와 실행기가 같은 볼륨으로 공유한다」를 전제한다(ChatWorkspaceAssigner 머리 주석). CS 실행기는 같은 컨테이너 경로 `/data/workspace` 에 다른 호스트 폴더 `workspace-wes-cs` 를 붙여서, 넘겨받은 경로가 실행기 안에 없다. 실행기는 「is not a directory, using default cwd」를 남기고 `/app` 에서 돌며 파일을 `/tmp` 에 만든다(ClaudeCliWorker.java:374, CR-181 15절 표 5).

### 2.2 59 (2026-11-14 01:10 읽기 조회)

| 순번 | 컨테이너 | 호스트 폴더 | 컨테이너 경로 |
|---|---|---|---|
| 1 | api, rag-pipeline, skill-sandbox | workspace | /data/workspace |
| 2 | 실행기 axopm, bidding, bp-office, bp-wes, lexflow, workmap | workspace | /data/workspace |
| 3 | 실행기 bp-wes-cs | workspace-wes-cs | /data/workspace |
| 4 | 실행기 bp-3plgw | workspace-3plgw | /data/workspace |

- api 환경 `AIMBASE_WORKSPACE_BASE=/data/workspace`, `AIMBASE_WORKSPACE_WHITELIST=/data/workspace`(서버 .env).
- `workspace/chat` 아래 세션 폴더 4529개, `workspace-wes-cs` 는 비어 있다.
- CS 실행기 기록의 「is not a directory」 는 19:57 재생성 뒤 0줄(그 전 기록은 CR-181 15절).

### 2.3 운영 chaws (같은 시각 읽기 조회)

| 순번 | 컨테이너 | 호스트 폴더 |
|---|---|---|
| 1 | api, 실행기 aimbase-agent | workspace |
| 2 | 실행기 bp-wes-cs | workspace-wes-cs |
| 3 | 실행기 bp-3plgw | workspace-3plgw |

- 59 와 같은 어긋남이다. `workspace/chat` 0개, `workspace-wes-cs` 비어 있음. bp-3plgw 실행기 기록의 「is not a directory」 20줄(워크플로우 run 폴더, 1절 넣지 않는 것).

## 3. 고칠 방향

- 경로를 옮겨 적지 않는다. api 와 실행기가 같은 호스트 폴더를 **같은 컨테이너 경로**로 붙이면, 서버가 잡은 경로를 실행기가 그대로 쓰고 실행기가 만든 파일을 서버가 그대로 읽는다.
- CS 실행기: `./workspace-wes-cs:/data/workspace-wes-cs`. api: 같은 줄을 더 붙인다.
- api 환경설정 `AIMBASE_WORKSPACE_RUNNER_BASES=cli-runner-bp-wes-cs=/data/workspace-wes-cs`(실행기 이름=폴더, 쉼표로 여러 개). 연결 설정 `agent_name` 이 표에 있으면 대화 폴더를 그 폴더 아래 `chat/{세션}` 에 잡고, 없으면 지금처럼 base 아래에 잡는다.
- 표의 폴더는 허용 폴더에 저절로 든다. 마운트, 표, 허용 목록 세 곳을 따로 맞추다 어긋난 앞 사례(CR-107 무렵 작업장 쓰기 실패)를 두 곳(compose 의 마운트와 표)으로 줄인다.
- 값은 환경마다 다른 배치 사실이라 DB 설정이 아니라 compose 환경설정에 둔다(application.yml 의 스킬 실행 컨테이너 주소와 같은 이유).
- 2026-11-14 01:11 컨타 승인(①, 사용자 권장안 승인 위임). 규모 소(새 API, 화면, 테이블 없음).

## 4. 구현

| 순번 | 곳 | 바뀐 것 |
|---|---|---|
| 1 | `WorkspaceProperties` | 설정 `aimbase.workspace.runner-bases`(환경 `AIMBASE_WORKSPACE_RUNNER_BASES`). 항목은 `실행기 이름=절대 경로`. 잘못 적은 항목은 기동을 멈춘다(건너뛰면 대화 폴더가 다시 실행기 밖에 잡힌다). 표의 폴더는 허용 폴더에 더한다. 허용 폴더가 비어 게이트가 꺼진 경우는 그대로 둔다 |
| 2 | `ChatWorkspaceAssigner.assign(세션, 실행기 이름)` | 실행기 이름이 표에 있으면 그 폴더 아래 `chat/{세션}`, 아니면 지금처럼 base 아래. 이미 잡힌 폴더는 바꾸지 않는다(BIZ-091). 기록 줄에 `runner=` 를 더했다 |
| 3 | `ConnectionAdapterFactory.routingAgentName` | 연결 설정 `agent_name` 을 어댑터를 만들지 않고 읽는다 |
| 4 | `OrchestratorEngine.autoAssignChatWorkspace` | 실행기 연결이면 그 연결의 `agent_name` 으로 폴더를 잡는다 |
| 5 | `docker-compose.prod.yml`, `docker-compose.chaws.yml` | api 에 `./workspace-wes-cs:/data/workspace-wes-cs` 와 `AIMBASE_WORKSPACE_RUNNER_BASES=cli-runner-bp-wes-cs=/data/workspace-wes-cs`. CS 실행기 작업 폴더를 `/data/workspace` 에서 `/data/workspace-wes-cs` 로 |
| 6 | 운용 가이드 v3.37.0 | 2-10 CS 실행기 절에 작업 폴더 경로, CR-107 run별 격리 절에 한 줄 |

- 파일 만들기 도구(CR-182)는 세션에 이미 잡힌 폴더를 그대로 쓰므로 바꾸지 않았다. API 연결은 실행기가 없어 지금처럼 base 아래다.
- `agent_name` 없이 머리글이나 user_ref 로 실행기를 정하는 연결은 표를 보지 않는다. CS 연결 4개는 모두 `agent_name=cli-runner-bp-wes-cs` 다(CR-173 1절).
- 단위시험: `WorkspacePropertiesTest` 8건(새 4건), `ChatWorkspaceAssignerTest` 4건(새), `WorkflowEngineWorkspaceTest` 4건 통과.
