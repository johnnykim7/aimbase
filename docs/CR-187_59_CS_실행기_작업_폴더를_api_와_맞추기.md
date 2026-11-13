# CR-187 59 CS 실행기 작업 폴더를 api 와 맞추기

> 설계 버전: 0.1 | 최종 수정: 2026-11-14 | 관련 CR: CR-181, CR-182, CR-173, CR-174

- 원천: 2026-11-13 19:41 사용자 /전승 「후속 CR 발번」. CR-181 완료 보고 「59 CS 실행기의 작업 폴더가 api 와 달라(workspace-wes-cs) 실행기가 직접 만든 파일이 대화에 올라오지 않음」
- 실행맵: [기록/실행맵_채팅_위젯_고도화.md](기록/실행맵_채팅_위젯_고도화.md) 12번 행(추가 갈래)
- 번호 대역: PRD-600 ~ 609, BIZ-260 ~ 264, FE-150 ~ 154, TC 130 ~ 134, 시험 영역 20(IT-20-001, 002), 마이그레이션 테넌트 V90
- 상태: 개발기 59 반영과 확인 끝(7절). 운영 chaws 반영 전

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

## 5. 59 반영과 확인 (2026-11-14)

| 순번 | 항목 | 값 |
|---|---|---|
| 1 | 반영 | dev bd73fb30 을 `./deploy.sh be` 다음 `./deploy.sh agent-wes-cs`. api 01:22 Started, 기동 뒤 ERROR 0줄. CS 실행기는 재등록됐고 작업 폴더가 `/data/workspace-wes-cs` 다. 나머지 실행기 7개 healthy |
| 2 | 대화 폴더 | CS 연결(`cli-runner-bp-wes-cs-001`)로 도구를 말하지 않고 「주간 회의 회의록을 docx 파일 하나로」 를 보냈다. api 가 `/data/workspace-wes-cs/chat/{세션}` 을 잡았고(기록 `runner=cli-runner-bp-wes-cs`), 실행기가 그 폴더에 `주간_회의.docx` 를 만들었다. 호스트 `workspace-wes-cs/chat/0453c5fa-e006-4985-aec4-149de4888324/주간_회의.docx`. CR-181 15절 표 5 때는 `/tmp` 였다 |
| 3 | 파일 카드 | 붙지 않았다. 파일이 `out/` 이 아니라 대화 폴더 바로 아래에 있다. 그림 `/tmp/claude-e2e/9ba9b044/99-fail.png` |

### 5.1 카드가 붙지 않는 원인

- 서버는 대화 폴더의 `out/` 만 훑는다(ArtifactService.java:43). 실행기에 「사용자에게 줄 파일은 ./out/ 에 두라」 고 알리는 것은 코드가 아니라 연결마다 적는 시스템 프롬프트 문구다(CR-132 규약).
- 59 bp_wes 읽기 조회: 공용 실행기 연결 `cli-runner-bp-wes-001`, work01~03 에는 「## 산출물(파일) 규약」 문구가 있다. CS 연결 4개(`cli-runner-bp-wes-cs-001`, work01~03)는 연결 시스템 프롬프트가 비어 있다. relocation-plan 3개는 프롬프트가 있으나 그 문구가 없고, field-issue 4개는 비어 있다.
- 폴더 어긋남은 이 건으로 풀렸고, 남은 것은 CS 연결에 그 규약이 없는 것이다.

### 5.2 고칠 방향 (승인 대기)

| 순번 | 방향 | 잃는 것 |
|---|---|---|
| 1 | 서버가 실행기 연결의 대화 폴더를 잡을 때 「산출물은 ./out/」 규약을 늘 덧붙인다. 문구는 프롬프트 템플릿 한 건(테넌트 마이그레이션 V90)에 두고 CLI 기본 프롬프트 뒤에 붙인다. 연결마다 적지 않아도 된다 | 없음. 이미 문구가 있는 연결은 같은 말이 두 번 실린다 |
| 2 | CS 연결 4개의 시스템 프롬프트에 공용 연결과 같은 문구를 넣는다(59, 운영 DB 데이터 수정) | 새 실행기 연결마다 같은 빈칸이 다시 생긴다. 연결 프롬프트가 비어 있던 CS 연결에 프롬프트가 처음 생긴다 |

- 2026-11-14 01:35 컨타 승인(①, 사용자 권장안 승인 위임). 규모 소 유지(틀 한 건, 붙이는 곳 두 군데. 새 API, 화면, 테이블 없음).

## 6. 파일 위치 안내 (5.2 ①)

| 순번 | 곳 | 바뀐 것 |
|---|---|---|
| 1 | 테넌트 마이그레이션 V90 | 프롬프트 틀 `widget.artifact-out`(영문). 사용자에게 줄 파일은 작업 폴더 `./out/` 에, 먼저 `mkdir -p ./out`, 다른 곳(`/tmp` 등)은 사용자에게 가지 않는다, 고칠 때는 같은 이름으로, 답에는 파일 이름만. 관리자가 고친 틀은 덮지 않는다(`ON CONFLICT DO NOTHING`) |
| 2 | `ChatFeatureSupport.withArtifactGuide` | 틀을 이번 질문 몫 시스템 안내로 마지막 질문 바로 앞에 넣는다. 틀이 없거나 비활성이면 넣지 않고 경고 기록 한 줄 |
| 3 | `OrchestratorEngine` 비스트리밍, 스트리밍 두 경로 | 작업 폴더가 정해진 직후 붙인다 |

### 6.1 붙는 조건

- 세 가지를 모두 만족할 때만 붙는다. ① 연결이 실행기(Claude CLI) 연결 ② 세션 작업 폴더가 있다(서버가 잡았거나 요청이 줬다) ③ 워크플로우 단계나 서브에이전트가 아닌 대화 요청.
- 파일 만들기 설정(CR-182 `chat.file-create.enabled`)과는 묶지 않았다. 대화 파일 카드는 그 설정과 무관하게 `out/` 을 늘 훑고(ArtifactService), 실행기는 그 도구 없이도 파이썬으로 파일을 만든다(5절 표 2). 파일 만들기 도구는 스스로 `out/` 에 쓴다.
- 이번 질문 몫으로 붙이는 까닭: 실행기는 연결 시스템 프롬프트가 있으면 이 표시가 없는 시스템 안내를 CLI 에 넘기지 않고, 이어지는 CLI 세션에서는 지난 질문의 시스템 안내를 다시 보내지 않는다(ClaudeCliWorker.java:622, 656). 이 표시가 있어야 연결 프롬프트를 둔 실행기에도, 둘째 질문부터도 간다.

### 6.2 영향 범위

| 순번 | 대상 | 영향 |
|---|---|---|
| 1 | 실행기 연결의 모든 테넌트 대화 | 질문마다 안내 네 줄(약 450자)이 더 실린다. 파일을 만들지 않는 질문에는 따르는 행동이 없다 |
| 2 | 연결 프롬프트에 같은 규약이 있는 연결(59 bp_wes 공용 실행기 연결 4개 등) | 같은 말이 두 번 실린다. 내용이 같은 방향이라 충돌하지 않는다 |
| 3 | API 연결 대화 | 작업 폴더가 없어 붙지 않는다. 지금과 같다 |
| 4 | 워크플로우 AGENT_CALL, 서브에이전트 | 붙지 않는다. 지금과 같다 |
| 5 | 틀을 끄고 싶은 테넌트 | 「프롬프트 템플릿」 에서 비활성으로 두면 붙지 않는다(운용 가이드 2-21) |

### 6.3 시험

- 단위시험: `ChatFeatureSupportTest` 8건(새 2건), `OrchestratorOneShotWorkerTest` 6건, `OrchestratorWidgetMemoryTest` 6건, `OrchestratorTurnInstructionTest` 3건, 앞 절 12건 통과.
- 로컬(18187, 테넌트 cr160_local, 로컬 실행기 `cr187-runner` 를 따로 붙인 실행기로 표에 둠): V90 을 migrate API 로 적용(성공 1). 도구를 말하지 않고 docx 를 달라고 하면 실행기가 `runner-cs/chat/{세션}/out/주간회의.docx` 에 만들고 답에 파일 카드(`artifacts`)가 실렸다. 같은 요청을 틀을 끄고(캐시도 비우고) 보내면 실행기가 CLI 자기 임시 폴더에 만들고 카드가 없었다. 틀을 다시 켰다.
- 위젯이 쓰는 스트리밍 경로도 같다. xlsx 를 달라고 하면 `out/분기매출.xlsx` 에 만들고 done 이벤트에 파일 카드가 실렸다.

## 7. 개발기 59 2차 반영과 확인 (2026-11-14 01:46)

| 순번 | 항목 | 값 |
|---|---|---|
| 1 | 반영 | dev 3a8c2094 을 `./deploy.sh be`. 실행기는 바뀐 것이 없어 다시 반영하지 않았다 |
| 2 | 기동 | api 01:46 Started, 기동 뒤 ERROR 0줄. 테넌트 V90 이 기동 때 적용됐고 bp_wes 에 `widget.artifact-out` 이 활성으로 있다 |
| 3 | 파일 카드 | CS 연결로 도구를 말하지 않고 docx 를 달라고 하자 실행기가 `workspace-wes-cs/chat/{세션}/out/주간회의.docx` 에 만들었고 답에 「주간회의.docx (36KB)」 칩이 붙었다 |
| 4 | 원본 없는 이진 파일 고치기(CR-181 15절에 남은 미확인) | 칩을 눌러 옆 창에 「제목을 '월간 회의' 로 바꿔 주세요」 를 보내자 판 2 / 2, 옆 창 제목 「월간 회의」, 답에 「주간회의.docx · 버전 2」 카드. 요청에 `document_edit`(파일)이 실렸고 판 저장소에 FILE 판 2개 |
| 5 | 실행기 기록 | 반영 뒤 「is not a directory」 0줄 |

- 그림 `/tmp/claude-e2e/9ba9b044/21-runner-file-answer.png`, `22-runner-file-pane.png`, `23-runner-file-edited.png`.
- 운영 chaws 는 반영하지 않았다. compose(1차)와 api(1, 2차)를 함께 반영해야 하며 따로 승인받는다.

## 8. 통합 시험항목 (영역 20)

| 순번 | 항목 | 무엇을 | 까닭 |
|---|---|---|---|
| 1 | IT-20-001 새로 | CS 실행기가 저장 폴더를 듣지 않고 스스로 만든 docx 에 칩이 붙고, 같은 대화의 둘째 파일(xlsx)도 붙고, 파일이 workspace-wes-cs 쪽 out/ 에만 있다 | 7절 표 3 을 시험으로 남긴다. 둘째 파일은 이어지는 CLI 세션에도 안내가 가는지(6.1 셋째 줄) 본다. 파일 만들기 도구를 끄고 봐야 안내 틀을 거친다 |
| 2 | IT-20-002 새로 | 안내 틀을 비활성으로 두어도 답이 오고 api 기록에 틀이 없다는 경고가 남는다 | 6.2 표 5 의 끄는 길과 틀이 없을 때의 실패 경로 |
| 3 | IT-15-003 전제 고침 | 「실행기와 api 는 서로 다른 볼륨을 쓴다」 를 「대화 작업 폴더(workspace-wes-cs)를 같은 경로로 함께 본다(CR-187)」 로 | 이 CR 로 전제가 틀어졌다 |
| 4 | IT-14-002 전제 근거와 기대결과 1 문구 | 실행기 작업 폴더 전제의 근거를 CR-187 로, 기대결과 1 을 「제안서.pptx 첫 장이 한글 표지 제목이 읽히는 그림으로」 로 | 원본 없는 파일 고치기(7절 표 4)는 이 항목이 덮는다. 문구 점검에 걸린 줄을 함께 고쳤다 |

- 판정 방식은 모두 합동이다. 서버 판정 조회와 회차 실행은 하지 않았다(사용자 지시 없음).

