# 영역 24 http_request 주소 값 인증과 가림 시험항목. 근거는 CR-197 문서 3절(3-1 주소 값 싣기, 3-2 기록 가림,
# 3-2-1 응답에 되돌아온 인증값 가림, 3-3 연결 조회 응답 가림), 운용 가이드 § 3-1, API 가이드 § 7-6 이다.
# 받는 쪽은 받은 요청 주소를 응답 본문 link 와 응답 머리값 X-Echo 로 되돌려 주는 수신기다(CR-197 7절 로컬 확인과 같은 꼴).

PRE = ('개발기 테넌트의 API 키, 받은 요청 주소를 응답 본문 link 와 응답 머리값 X-Echo 로 되돌려 주는 수신기, '
       '그 수신기를 가리키는 HTTP 연결 1개(auth type API_KEY, in query, name OC, value_env 에 시험용 키 값). '
       '그 연결로 path /DRF/lawSearch.do, query 에 target prec 와 OC wf-override 를 실은 http_request 단계 하나짜리 워크플로우 1개')

ITEMS = [

dict(id='IT-24-001', flow='주소 값 인증 싣기', kind='정상', title='연결 인증키가 요청 주소 값 OC 로 받는 쪽에 가고, 워크플로우가 보낸 같은 이름 값은 버려진다',
     pre=PRE,
     steps='1. 워크플로우를 실행하고 끝날 때까지 조회한다\n'
           '2. 수신기가 받은 요청 주소를 본다',
     expect='1. 실행이 completed 로 끝나고 http_request 단계 결과의 응답 코드가 200 이다\n'
            '2. 주소에 OC 가 한 번만 있고 그 값이 연결 키를 URL 인코딩한 값이다. wf-override 와 소문자 oc 가 없고 target=prec 는 있다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=[], src=['CR-197']),

dict(id='IT-24-002', flow='주소 값 인증 가림', kind='정상', title='실행 조회, 실행 기록, 서버 로그 어디에도 연결 키가 남지 않고 되돌아온 키는 *** 로 바뀐다',
     pre='IT-24-001 의 실행',
     steps='1. 실행 조회(GET /api/v1/workflows/runs/{runId})의 http_request 단계 결과를 본다\n'
           '2. 그 실행의 단계 사건 기록(실행 사건 조회)과 감사 기록을 본다\n'
           '3. 실행 조회 응답, 사건 기록, 테넌트 DB 의 workflow_runs 와 workflow_run_events 행, 그 시각 api 로그에서 연결 키 값과 그 URL 인코딩 꼴을 찾는다',
     expect='1. 단계 결과 첫 줄 주소, 응답 본문 link, 응답 머리값 x-echo 의 OC 값이 모두 *** 다\n'
            '2. 감사 기록 uri 와 responseSummary 의 OC 값이 *** 다\n'
            '3. 키 값이 어디에도 없다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=[], src=['CR-197']),

dict(id='IT-24-003', flow='연결 조회 가림', kind='정상', title='연결 조회 응답의 auth.value 는 *** 이고, *** 를 그대로 실어 수정해도 저장된 키가 지켜진다',
     pre='개발기 테넌트의 API 키, auth 에 평문 value 를 둔 HTTP 연결 1개(시험용 값)',
     steps='1. 그 연결을 상세 조회(GET /api/v1/connections/{id})하고 종류 거름 목록(GET /api/v1/connections?type=HTTP)과 쪽 목록을 조회한다\n'
           '2. 상세 조회 응답의 config 를 그대로 실어 이름만 바꿔 수정(PUT /api/v1/connections/{id})한다\n'
           '3. 그 연결로 http_request 를 한 번 부르고 받는 쪽이 받은 OC 값을 본다',
     expect='1. 세 응답 모두 config.auth.value 가 *** 이고 value_env, name, in 은 그대로다\n'
            '2. 200 이고 응답의 auth.value 가 *** 다\n'
            '3. 받는 쪽 OC 값이 처음 저장한 키다(*** 가 아니다)',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=[], src=['CR-197']),

dict(id='IT-24-004', flow='머리값 인증 회귀', kind='회귀', title='머리값 방식 연결은 키를 주소에 싣지 않고 머리값으로만 보낸다',
     pre='IT-04-011 의 연결(auth API_KEY, in header)과 그 알림 실행',
     steps='1. 그 알림 실행의 http_request 감사 기록 uri 와 requestHeaders 를 본다',
     expect='1. uri 에 인증 이름이 주소 값으로 없고 requestHeaders 의 인증 머리값이 *** 다(IT-04-015 와 같다)',
     judge=['서버'],
     pair={1: [1]},
     req=[], src=['CR-197', 'CR-164']),

]
