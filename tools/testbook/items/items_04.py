# 영역 04 개발 작업 상태 알림 시험항목. 근거는 CR-164(T1-1 PRD-350 ~ 353, T1-3 BIZ-129 ~ 132, API 가이드 § 26,
# 운용 가이드 시나리오 K)다. 알림 워크플로우와 연결 모양은 CR-164 문서 4.6 절이다.
# 실행 실패, 승인 거절, 자식 run 처럼 서버에 그대로 남는 결과는 서버 판정으로 본다.

ITEMS = [

dict(id='IT-04-001', flow='개발 작업 상태 보고', kind='정상', title='상태 API 로 새 작업을 만들고 단계와 상태를 바꾸면 바뀔 때마다 변경 줄이 남는다',
     pre='개발기 테넌트의 API 키, 그 테넌트에서 쓴 적 없는 작업 번호 1개',
     steps='1. 그 작업 번호로 상태를 보고(POST /api/v1/dev-work-items/{work_item_id}/status, 본문 feature_name, stage "기능", status IN_PROGRESS)한다\n'
           '2. 같은 작업 번호로 stage "실증", status COMPLETED, evidence_url 에 https 주소를 실어 다시 보고한다\n'
           '3. 그 작업 상세를 조회(GET /api/v1/dev-work-items/{work_item_id})한다',
     expect='1. 200 과 함께 item.status 가 IN_PROGRESS, change.source 가 API, change.changed_at 이 밀리초까지 있는 UTC 시각(끝이 Z)으로 온다\n'
            '2. 200 과 함께 item 의 stage 가 실증, status 가 COMPLETED, evidence_url 이 보낸 주소이고 change 가 null 이 아니다\n'
            '3. changes 가 2줄이고 최근순으로 COMPLETED(실증), IN_PROGRESS(기능)다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-350'], src=['CR-164']),

dict(id='IT-04-002', flow='개발 작업 상태 보고', kind='예외', title='단계, 상태, 증빙이 같은 보고를 다시 하면 변경 줄도 알림 실행도 생기지 않는다',
     pre='IT-04-001 로 만든 작업, 이벤트 트리거 DEV_WORK_ITEM_STATUS_CHANGED 를 단 활성 알림 워크플로우 1개(CR-164 문서 4.6 절 모양)',
     steps='1. IT-04-001 의 2번 보고와 stage, status, evidence_url 이 같은 본문으로 다시 보고한다\n'
           '2. 그 작업 상세를 조회한다\n'
           '3. 30초 뒤 알림 워크플로우 실행 목록을 조회(GET /api/v1/workflows/{id}/runs)한다',
     expect='1. 200 과 함께 change 가 null 이고 item.status_changed_at 이 2번 보고 때 값 그대로다\n'
            '2. changes 줄 수가 다시 보고하기 전과 같다\n'
            '3. 다시 보고한 시각 뒤에 시작한 알림 실행이 없다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-350'], src=['CR-164']),

dict(id='IT-04-003', flow='개발 작업 상태 보고', kind='예외', title='칸이 틀린 상태 보고는 저장되지 않고 틀린 칸 이름으로 400 이 온다',
     pre='개발기 테넌트의 API 키, 그 테넌트에서 쓴 적 없는 작업 번호 1개',
     steps='1. 그 작업 번호로 feature_name 없이 stage, status IN_PROGRESS 만 보고한다\n'
           '2. 101자 작업 번호로 feature_name, stage, status IN_PROGRESS 를 보고한다\n'
           '3. 그 작업 번호로 feature_name, stage 와 status "DONE" 을 보고한다\n'
           '4. 그 작업 번호로 feature_name, stage, status IN_PROGRESS 와 evidence_url "ftp://example.com/a" 를 보고한다\n'
           '5. 그 작업 번호의 상세를 조회한다',
     expect='1. 400 과 dev_work_item_invalid:feature_name 이 온다\n'
            '2. 400 과 dev_work_item_invalid:work_item_id 가 온다\n'
            '3. 400 과 dev_work_item_invalid:status 가 온다\n'
            '4. 400 과 dev_work_item_invalid:evidence_url 이 온다\n'
            '5. 404 가 온다. 틀린 보고로 작업이 만들어지지 않았다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-350'], src=['CR-164']),

dict(id='IT-04-004', flow='실행과 개발 작업 잇기', kind='정상', title='실행 입력에 _dev_work_item 을 실은 실행이 시작에 IN_PROGRESS, 성공으로 끝나면 COMPLETED 로 작업을 바꾼다',
     pre='개발기 테넌트의 API 키, 단계가 1개 이상이고 성공으로 끝나는 워크플로우 1개, 쓴 적 없는 작업 번호 1개',
     steps='1. 실행 입력에 _dev_work_item(work_item_id 그 번호, feature_name, stage "실증")을 실어 워크플로우를 실행(POST /api/v1/workflows/{id}/run)하고 끝날 때까지 실행을 조회(GET /api/v1/workflows/runs/{runId})한다\n'
           '2. 그 작업 상세를 조회한다',
     expect='1. 실행이 completed 로 끝난다\n'
            '2. item.status 가 COMPLETED 이고 last_workflow_run_id 가 그 실행 번호다\n'
            '3. changes 가 2줄이고 최근순으로 COMPLETED, IN_PROGRESS 이며 두 줄 모두 source 가 WORKFLOW_RUN, workflow_run_id 가 그 실행 번호다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [2]},
     req=['PRD-351'], src=['CR-164']),

dict(id='IT-04-005', flow='실행과 개발 작업 잇기', kind='정상', title='실패로 끝난 실행은 작업을 FAILED 로 바꾸고 그 바뀜으로 알림 실행이 새로 생긴다',
     pre='개발기 테넌트의 API 키, 없는 주소를 불러 실패로 끝나는 http_request 단계를 가진 워크플로우 1개, IT-04-002 의 알림 워크플로우, 쓴 적 없는 작업 번호 1개',
     steps='1. 실행 입력에 _dev_work_item 을 실어 실패하는 워크플로우를 실행하고 끝날 때까지 조회한다\n'
           '2. 그 작업 상세를 조회한다\n'
           '3. 알림 워크플로우 실행 목록에서 그 작업 번호가 입력에 있는 실행을 끝날 때까지 조회한다',
     expect='1. 실행이 failed 로 끝난다\n'
            '2. item.status 가 FAILED 이고 맨 위 변경 줄의 workflow_run_id 가 그 실행 번호다\n'
            '3. 입력 status 가 FAILED 이고 reported_at 이 그 변경 줄 changed_at 과 같은 알림 실행이 completed 로 끝난다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-351', 'PRD-352'], src=['CR-164']),

dict(id='IT-04-006', flow='실행과 개발 작업 잇기', kind='정상', title='실행을 중지하면 작업이 CANCELLED 로 바뀐다',
     pre='개발기 테넌트의 API 키, 첫 단계가 30초 넘게 걸리고 뒤에 단계가 하나 더 있는 워크플로우 1개, 쓴 적 없는 작업 번호 1개',
     steps='1. 실행 입력에 _dev_work_item 을 실어 실행하고, 첫 단계가 도는 동안 실행 중지(POST /api/v1/workflows/runs/{runId}/cancel)를 부른 뒤 끝날 때까지 조회한다\n'
           '2. 그 작업 상세를 조회한다',
     expect='1. 실행이 cancelled 로 끝난다\n'
            '2. item.status 가 CANCELLED 이고 맨 위 변경 줄의 workflow_run_id 가 그 실행 번호다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-351'], src=['CR-164']),

dict(id='IT-04-007', flow='실행과 개발 작업 잇기', kind='예외', title='_dev_work_item 칸이 틀린 실행 요청은 실행을 만들지 않는다',
     pre='개발기 테넌트의 API 키, 워크플로우 1개',
     steps='1. 그 워크플로우의 실행 목록 건수를 본다\n'
           '2. 실행 입력 _dev_work_item 에 work_item_id 없이 feature_name, stage 만 실어 실행을 요청한다\n'
           '3. 그 워크플로우의 실행 목록을 다시 조회한다',
     expect='1. 실행 목록 건수가 조회된다\n'
            '2. 400 과 dev_work_item_invalid:work_item_id 가 온다\n'
            '3. 실행 목록 건수가 1번과 같다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-351'], src=['CR-164']),

dict(id='IT-04-008', flow='실행과 개발 작업 잇기', kind='예외', title='승인 대기는 진행 중으로 두고 승인을 거절해 실패한 실행은 작업을 FAILED 로 바꾼다',
     pre='개발기 테넌트의 API 키, HUMAN_INPUT 단계를 가진 워크플로우 1개, 쓴 적 없는 작업 번호 1개',
     steps='1. 실행 입력에 _dev_work_item 을 실어 실행하고 승인 대기가 될 때까지 조회한다\n'
           '2. 그 작업 상세를 조회한다\n'
           '3. 그 실행을 거절(POST /api/v1/workflows/runs/{runId}/approve, 본문 approved false)하고 끝날 때까지 조회한다\n'
           '4. 그 작업 상세를 다시 조회한다',
     expect='1. 실행 상태가 pending_approval 이다\n'
            '2. item.status 가 IN_PROGRESS 다\n'
            '3. 실행이 failed 로 끝난다\n'
            '4. item.status 가 FAILED 이고 맨 위 변경 줄의 workflow_run_id 가 그 실행 번호다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-351'], src=['CR-164']),

dict(id='IT-04-009', flow='실행과 개발 작업 잇기', kind='예외', title='단계가 없는 워크플로우 실행도 작업을 COMPLETED 로 바꾼다',
     pre='개발기 테넌트의 API 키, 단계가 없는(steps 빈 목록) 워크플로우 1개, 쓴 적 없는 작업 번호 1개',
     steps='1. 실행 입력에 _dev_work_item 을 실어 실행하고 끝날 때까지 조회한다\n'
           '2. 그 작업 상세를 조회한다',
     expect='1. 실행이 completed 로 끝난다\n'
            '2. item.status 가 COMPLETED 이고 changes 가 2줄(COMPLETED, IN_PROGRESS)이다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-351'], src=['CR-164']),

dict(id='IT-04-010', flow='실행과 개발 작업 잇기', kind='예외', title='SUB_WORKFLOW 로 부른 자식 실행은 작업 상태를 바꾸지 않고 부모 실행만 작업에 잇는다',
     pre='개발기 테넌트의 API 키, SUB_WORKFLOW 단계로 성공하는 다른 워크플로우를 부르는 부모 워크플로우 1개, 쓴 적 없는 작업 번호 1개',
     steps='1. 실행 입력에 _dev_work_item 을 실어 부모 워크플로우를 실행하고 끝날 때까지 조회한다\n'
           '2. 그 작업 상세를 조회한다',
     expect='1. 부모 실행이 completed 로 끝난다\n'
            '2. changes 가 2줄(COMPLETED, IN_PROGRESS)이고 두 줄의 workflow_run_id 가 모두 부모 실행 번호다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-351'], src=['CR-164']),

dict(id='IT-04-011', flow='상태 변경 알림', kind='정상', title='작업 상태가 바뀌면 이벤트 트리거 알림 워크플로우가 실행되어 bp-office 창구로 보낸다',
     pre='개발기 테넌트의 API 키, bp-office 개발 작업 상태 창구(/api/v1/inbound/dev-agent-status)를 가리키는 HTTP 연결(auth API_KEY, in header, name X-Service-Token, value_env 는 Aimbase 서버 환경변수 이름), '
         '그 연결로 보내는 IT-04-002 의 알림 워크플로우, 쓴 적 없는 작업 번호 1개',
     steps='1. 그 작업 번호로 status IN_PROGRESS 를 보고한다\n'
           '2. 알림 워크플로우 실행 목록에서 그 작업 번호가 입력에 있는 실행을 끝날 때까지 조회한다\n'
           '3. 그 알림 실행 상세의 입력과 http_request 단계 결과를 본다',
     expect='1. 200 과 함께 change 가 null 이 아니다\n'
            '2. 알림 실행 1건이 completed 로 끝난다\n'
            '3. 알림 실행 입력의 work_item_id, status, change_id, reported_at 이 1번 change 의 work_item_id, status, id, changed_at 과 같다\n'
            '4. http_request 단계 결과의 응답 코드가 200 이다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [3]},
     req=['PRD-352'], src=['CR-164']),

dict(id='IT-04-012', flow='상태 변경 알림', kind='정상', title='알림 실행을 다시 실행해도 reported_at 은 처음 바뀐 시각 그대로 간다',
     pre='IT-04-011 의 알림 실행',
     steps='1. 그 알림 실행을 재실행(POST /api/v1/workflows/runs/{runId}/rerun)하고 새 실행이 끝날 때까지 조회한다\n'
           '2. 새 실행의 입력을 본다',
     expect='1. 새 실행이 completed 로 끝난다\n'
            '2. 새 실행 입력의 reported_at 과 change_id 가 IT-04-011 알림 실행 입력과 같다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-352'], src=['CR-164']),

dict(id='IT-04-013', flow='상태 변경 알림', kind='예외', title='알림 실행이 끝나도 작업 상태가 다시 바뀌거나 알림이 또 나가지 않는다',
     pre='IT-04-011 을 마친 작업',
     steps='1. IT-04-011 의 알림 실행이 끝나고 30초 뒤 그 작업 상세를 조회한다\n'
           '2. 알림 워크플로우 실행 목록에서 그 작업 번호가 입력에 있는 실행을 센다(IT-04-012 재실행 분은 뺀다)',
     expect='1. changes 가 IT-04-011 1번 보고의 1줄뿐이다\n'
            '2. 그 작업 번호의 알림 실행이 1건뿐이다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-352'], src=['CR-164']),

dict(id='IT-04-014', flow='상태 변경 알림', kind='예외', title='알림 워크플로우 하나가 실패해도 다른 알림 워크플로우는 보내고 상태 기록은 남는다',
     pre='IT-04-011 의 알림 워크플로우, 같은 이벤트 트리거를 달고 없는 주소로 보내 실패하는 알림 워크플로우 1개 더, 쓴 적 없는 작업 번호 1개',
     steps='1. 그 작업 번호로 status IN_PROGRESS 를 보고한다\n'
           '2. 두 알림 워크플로우 실행 목록에서 그 작업 번호가 입력에 있는 실행을 끝날 때까지 조회한다\n'
           '3. 그 작업 상세를 조회한다',
     expect='1. 200 과 함께 change 가 null 이 아니다\n'
            '2. IT-04-011 알림 워크플로우의 실행이 completed 로 끝나고 http_request 단계 응답 코드가 200 이다\n'
            '3. item.status 가 IN_PROGRESS 이고 changes 가 1줄이다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-352'], src=['CR-164']),

dict(id='IT-04-015', flow='http_request 머리값 가림', kind='정상', title='연결 인증 머리값과 입력의 같은 이름 머리값이 실행 기록에 *** 로만 남고 받는 쪽에는 값이 간다',
     pre='IT-04-011 의 연결과 알림 실행, 같은 연결로 입력 headers 에 소문자 x-service-token 과 일반 머리값 X-Trace-Id 를 실은 http_request 단계 워크플로우 1개',
     steps='1. IT-04-011 알림 실행의 http_request 감사 기록(auditPayload.requestHeaders)을 본다\n'
           '2. 입력 headers 를 실은 워크플로우를 실행하고 끝날 때까지 조회한 뒤 그 http_request 감사 기록을 본다\n'
           '3. IT-04-011 알림 실행의 입력, 단계 결과, 감사 기록 전부에서 연결 환경변수에 든 토큰 값을 찾는다',
     expect='1. requestHeaders 의 X-Service-Token 값이 *** 다\n'
            '2. requestHeaders 의 x-service-token 값이 *** 이고 X-Trace-Id 는 보낸 값 그대로다\n'
            '3. 토큰 값이 어디에도 없다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-353'], src=['CR-164']),

]
