# 영역 08 품고 API 변경 수리 시험항목. 근거는 CR-174 문서 4절(4.1 흐름, 4.2 웹훅 입력, 4.3 엔진 변경, 4.4 워크플로우 정의,
# 4.5 실행기와 권한), 7절(로컬, 59, 운영 실측), 8절 2026-11-11 05:05(동시 인입 OOM)와 05:17(변환표 정규화 규칙)이다.
# 대상 기능은 T1-1 PRD-363 ~ 367 이라 req 에 그 번호를 적고 src 에 CR 번호를 적는다.

ITEMS = [

dict(id='IT-08-001', flow='수리 요청 접수', kind='정상', title='3PLGW 웹훅으로 수리 워크플로우를 부르면 run 이 바로 만들어지고 수리 단계가 CLI 실행기에서 돈 뒤 승인 대기로 선다',
     pre='테넌트 bp_3plgw 의 소비앱 키(서버 안에서만 쓴다), 워크플로우 poomgo-change-repair, 실행기 aimbase-agent-3plgw 가 healthy. 계약과 표본 주소가 열리는 변경 1건',
     steps='1. 서버 안에서 X-API-Key 와 X-Tenant-Id 를 붙여 POST /api/v1/workflows/poomgo-change-repair/run 에 4.2 의 입력(_callback 포함)을 보낸다\n'
           '2. 응답의 data.id 로 GET /api/v1/workflows/runs/{run_id} 를 수리 단계가 끝날 때까지 본다\n'
           '3. 실행기 기록에서 그 run 의 main worker spawned 줄을 찾는다\n'
           '4. 테넌트 DB pending_approvals 에서 그 run 의 줄을 본다',
     expect='1. 수리 단계가 끝나기 전에 응답이 오고 data.id 가 run_id 다\n'
            '2. status 가 pending_approval 이고 stepResults.repair.structured_data 에 result_kind, summary, changed, contract_proposal, pr, tests, failure_reason 일곱 칸이 있다\n'
            '3. 그 줄의 연결이 묶음 cli-runner-bp-3plgw-rr 의 계정 연결(work01, work02, work03) 중 하나다\n'
            '4. 줄의 status 가 pending 이고 timeout_at 이 만든 시각의 72시간 뒤다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-366'], src=['CR-174'], pri='High'),

dict(id='IT-08-002', flow='수리 요청 접수', kind='예외', title='_callback 이 틀린 수리 요청은 400 으로 거절되고 run 을 만들지 않는다',
     pre='platform-core 단위시험(RunCompletionCallbackTest, WorkflowEngineCallbackTest). 서버 확인은 bp_3plgw 소비앱 키',
     steps='1. _callback.connection_id 를 비우거나 없는 연결 id 로 두고 실행을 부른다\n'
           '2. _callback.connection_id 를 HTTP 가 아닌 연결(CLI 연결) id 로 두고 실행을 부른다\n'
           '3. _callback.path 를 / 로 시작하지 않는 글자로 두고 실행을 부른다\n'
           '4. 1 부터 3 뒤에 그 워크플로우의 run 목록을 본다',
     expect='1. 400 이고 오류 글자가 callback_invalid:connection_id 다\n'
            '2. 400 이고 오류 글자가 callback_invalid:connection_id 다\n'
            '3. 400 이고 오류 글자가 callback_invalid:path 다\n'
            '4. run 수가 요청 전과 같다',
     judge=['단위', '단위', '단위', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-363'], src=['CR-174'], pri='High'),

dict(id='IT-08-003', flow='실행 종료 회신', kind='정상', title='run 이 어떤 상태로 끝나도 _callback 의 연결과 경로로 결과를 한 번 보내고, 실패하면 정한 횟수만큼 다시 보낸다',
     pre='platform-core 단위시험(RunCompletionCallbackTest). 회신 재시도 3회, 간격 5000ms 기본값',
     steps='1. result_step 단계에 structured_data 가 있는 run 을 completed 로 끝낸다\n'
           '2. result_step 단계에 structured_data 가 없고 output 만 있는 run 을 failed 로 끝낸다\n'
           '3. 자식 run(parent_run_id 있음)과 _callback 이 없는 run 을 끝낸다\n'
           '4. 회신 POST 가 계속 5xx 를 돌려주게 하고 run 을 끝낸다',
     expect='1. 연결 bp-3plgw 의 path 로 POST 가 한 번 가고 본문의 run_id, workflow_id, status 가 그 run 의 값, result 가 그 structured_data 다\n'
            '2. 본문 status 가 failed, error 가 run 의 error, result 가 그 output 글자다\n'
            '3. 회신을 보내지 않는다\n'
            '4. 처음 1번과 다시 3번을 보낸 뒤 그 run 의 이벤트에 CALLBACK_FAILED 가 한 건 남는다',
     judge=['단위', '단위', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-363'], src=['CR-174'], pri='High'),

dict(id='IT-08-004', flow='승인 시한', kind='예외', title='승인 시한을 지난 대기 줄은 timeout 으로 닫히고 그 run 은 approval_timeout 으로 실패한다',
     pre='platform-core 단위시험(ApprovalTimeoutSweeperTest). 감시 주기 60초 기본값',
     steps='1. timeout_at 이 지난 pending 줄과 pending_approval 인 그 run 을 두고 감시를 한 번 돌린다\n'
           '2. 승인 API 로 이미 풀린(approved) 줄과 running 인 그 run 을 두고 감시를 돌린다\n'
           '3. timeout_at 이 아직 남은 pending 줄을 두고 감시를 돌린다\n'
           '4. 닫을 줄이 없는 상태로 감시를 돌리고 기록을 본다',
     expect='1. 줄이 status timeout 이고 resolved_at 이 채워진다\n'
            '2. 그 run 이 failed 이고 error.reason 이 approval_timeout, error.detail 이 그 timeout_at 이다\n'
            '3. 줄과 run 이 바뀌지 않는다\n'
            '4. 줄과 run 이 바뀌지 않는다\n'
            '5. 기록에 감시 줄이 남지 않는다',
     judge=['단위', '단위', '단위', '단위', '단위'],
     pair={1: [1], 2: [1], 3: [2], 4: [3], 5: [4]},
     req=['PRD-364'], src=['CR-174'], pri='High'),

dict(id='IT-08-005', flow='실행기 권한', kind='정상', title='수리 실행기는 3PLGW 내부 API 와 GitHub 에 자기 키로 들어가고 운영 DB 자격은 갖지 않는다',
     pre='개발기(59) 또는 운영 chaws 의 aimbase-agent-3plgw 컨테이너(서버 안에서만 본다. 키 값은 적지 않는다)',
     steps='1. 컨테이너 안에서 머리글 X-Internal-Service-Key 에 TPLGW_INTERNAL_KEY 를 넣어 3PLGW 의 GET /api/v1/internal/poomgo-repair/contracts/active 를 부른다\n'
           '2. 같은 주소를 머리글 없이 부른다\n'
           '3. 컨테이너 안에서 gh auth status 를 부른다\n'
           '4. 컨테이너 env 에서 PG 로 시작하는 변수와 DB 비밀번호 변수를 찾는다\n'
           '5. 서버 폴더에서 bash build/chaws-bootstrap.sh smoke 3plgw 를 돌린다',
     expect='1. 200 이고 본문이 비어 있지 않은 JSON 배열이다\n'
            '2. 403 이다\n'
            '3. github.com 에 로그인된 계정이 한 줄 나온다\n'
            '4. 없다\n'
            '5. 워크플로우, 연결 셋, 묶음, 실행기 건강, git, gh, 토큰 로그인, 내부 키 유무가 모두 통과로 찍힌다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-367'], src=['CR-174'], pri='High'),

dict(id='IT-08-006', flow='수리 제안 규칙', kind='정상', title='수리 지시문이 값 변환표 표기를 기존 계약 표기와 정규화 규칙에 맞추게 한다',
     pre='워크플로우 poomgo-change-repair 가 등록된 테넌트 bp_3plgw(59 또는 운영). 소비앱 키',
     steps='1. GET /api/v1/workflows/poomgo-change-repair 로 정의를 받아 repair 단계 config.prompt 를 읽는다\n'
           '2. 레포 scripts/poomgo-change-repair.workflow.json 의 같은 글과 비교한다',
     expect='1. 지시문에 배송 속성은 「당일」「도착보장」「일반」 세 구분이라는 문구가 있다\n'
            '2. 지시문에 새 원문 값은 기존 구분에 먼저 매핑하고 맞는 구분이 없을 때만 새 구분을 summary 의 이유와 함께 낸다는 문구가 있다\n'
            '3. 지시문에 SAME_DAY 는 운송장 계약의 「당일」이지 「당일배송」이 아니라는 예가 있다\n'
            '4. 서버 정의의 지시문과 레포 파일의 지시문이 글자까지 같다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [1], 3: [1], 4: [2]},
     req=['PRD-366'], src=['CR-174'], pri='Medium'),

dict(id='IT-08-007', flow='동시 수리 요청', kind='정상', title='수리 요청 13건이 5초 간격으로 와도 실행기가 다시 시작되지 않고 Claude 프로세스가 상한을 지킨다',
     pre='운영 chaws 의 aimbase-agent-3plgw(프로세스 상한 4, 줄 대기 600초, mem_limit 3g). 계약과 표본 주소가 열리는 변경 13건(같은 변경의 change_id 만 바꿔도 된다)',
     steps='1. 요청 전 컨테이너의 RestartCount 와 claude 프로세스 수를 적는다\n'
           '2. 서버 안에서 5초 간격으로 13건을 POST /api/v1/workflows/poomgo-change-repair/run 으로 보낸다\n'
           '3. 보내는 동안과 그 뒤 10분까지 10초마다 컨테이너 안의 claude 프로세스 수를 센다\n'
           '4. 20분 뒤 13건의 run 상태와 컨테이너 RestartCount 를 본다\n'
           '5. 실행기 기록에서 process slot 줄을 찾는다',
     expect='1. 프로세스 수가 어느 때도 4 를 초과하지 않는다\n'
            '2. RestartCount 가 요청 전과 같다\n'
            '3. 13건 모두 pending_approval 이다\n'
            '4. waiting for a process slot 줄과 process slot acquired 줄이 있고 process slot wait timeout 줄이 없다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [3], 2: [4], 3: [4], 4: [5]},
     req=['PRD-367'], src=['CR-174'], pri='High'),

dict(id='IT-08-008', flow='승인 뒤 적용', kind='정상', title='승인하면 제안 종류에 따라 계약 활성화 또는 PR 병합이 불리고, 거절하면 run 이 실패로 끝나 회신만 간다',
     pre='승인 대기(pending_approval)인 run 셋. CONTRACT 제안 1건, CODE 제안(열린 PR) 1건, 거절할 1건. 3PLGW 와 GitHub 가 호출을 받는다',
     steps='1. CONTRACT 제안 run 을 승인 API 로 승인한다\n'
           '2. CODE 제안 run 을 승인한다\n'
           '3. 남은 run 을 거절한다\n'
           '4. 세 run 의 _callback 을 받는 3PLGW 쪽 기록을 본다',
     expect='1. activate 단계가 연결 bp-3plgw 의 POST .../changes/{change_id}/activate 를 불러 2xx 를 받고 run 이 completed 다\n'
            '2. merge 단계가 연결 github-bp-3plgw 의 PUT /repos/{repo}/pulls/{number}/merge 를 불러 PR 이 merged 가 되고 run 이 completed 다\n'
            '3. run 이 failed 이고 activate 와 merge 단계는 돌지 않는다\n'
            '4. 세 run 모두 회신이 한 번씩 왔고 status 가 차례로 completed, completed, failed 다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-366'], src=['CR-174'], pri='High'),

dict(id='IT-08-009', flow='응답 추출', kind='정상', title='CLI 응답에 중괄호가 섞인 진행 설명이 앞에 붙어도 마지막 JSON 객체를 결과로 고른다',
     pre='platform-core 단위시험(StructuredOutputNormalizerTest)',
     steps='1. 중괄호가 섞인 진행 설명 뒤에 결과 JSON 객체가 붙은 글을 정규화한다\n'
           '2. 결과 JSON 객체만 있는 글을 정규화한다',
     expect='1. 마지막 JSON 객체가 structured_data 로 들어가고 앞의 설명은 들어가지 않는다\n'
            '2. 그 JSON 객체가 그대로 structured_data 다',
     judge=['단위', '단위'],
     pair={1: [1], 2: [2]},
     req=['PRD-366'], src=['CR-174'], pri='Medium'),

dict(id='IT-08-010', flow='단계 변수', kind='정상', title='단계 틀의 {{run.id}}, {{run.workflow_id}}, {{run.session_id}} 가 그 run 의 값으로 바뀐다',
     pre='platform-core 단위시험(StepContextRunVarsTest)',
     steps='1. 세 변수가 든 틀을 run 문맥으로 치환한다',
     expect='1. 세 자리가 그 run 의 id, 워크플로우 id, 세션 id 로 바뀐다',
     judge=['단위'],
     pair={1: [1]},
     req=['PRD-365'], src=['CR-174'], pri='Low'),

]
