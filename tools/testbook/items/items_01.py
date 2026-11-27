# 영역 01 CLI 작업과 앱 도구 시험항목. 근거는 CR-152(T3-12 § 4.3), CR-153(T1-3 BIZ-125, T3-13 § 4.4, T3-5 TC-MCP-RES-001~005), CR-154(T1-1 PRD-107, T3-5 TC-AK-011~016), CR-155(운용 가이드 § 2-9), CR-156(T1-3 BIZ-126, T3-5 TC-AGR-001~006), CR-163(CR 문서 § 6), CR-168(T1-3 BIZ-141, T3-5 TC-RL-001~008)이다.
# CR-152, CR-153 의 대상 기능(PRD-306, PRD-281)은 T1-1 에 행이 없어 req·fc 를 비워 두고 src 에 CR 번호를 적는다. CR-154 도 같은 영역이라 같게 둔다.
# CR-169 항목(IT-01-017 ~ 025)의 근거는 T1-3 BIZ-142, T3-2 구독 수정 요청 스키마, T3-4 구독 관리, T3-5 TC-SUB-001 ~ 009 다.
# CR-173 항목(IT-01-026 ~ 028)의 근거는 CR-173 문서 12절(키 없는 서버의 임베딩)과 13절(세션 없는 요청 뒤 실행기 프로세스)이다.
# CR-191 항목(IT-01-031 ~ 039)의 근거는 T1-3 BIZ-153, T1-1 PRD-080, PRD-087, PRD-376, T3-2 테넌트 생성과 구독 수정과 플랜 목록 응답, T3-4 플랜, T3-5 TC-PLAN-001 ~ 012 다.
# 대상 기능이 T1-1 에 행이 없어 req 를 비워 두고 src 에 CR 번호를 적는다.
# CR-205 항목(IT-01-041 ~ 049)의 근거는 T1-1 PRD-652, T1-3 BIZ-297, BIZ-298, T3-2 공통 사항 요청 한도와 구독 수정과 플랜 목록 응답, T3-4 구독 관리와 플랜, T3-5 TC-RL-009 ~ 014, TC-PLAN-013 ~ 017 다.

ITEMS = [

dict(id='IT-01-001', flow='CLI 작업 시작', kind='정상', title='CLI 작업이 앱 도구 연결 뒤에 첫 턴을 시작해 앱 도구를 부른다',
     pre='앱 도구 서버(MCP)가 붙은 CLI 연결(AIMBASE 모드)과 그 연결로 앱 도구를 부르는 AGENT_CALL 단계가 있는 워크플로우 1개',
     steps='1. 실행기(aimbase-agent)를 다시 띄운 직후 그 워크플로우를 실행한다\n'
           '2. 실행이 끝나면 워크플로우 실행 기록(단계 이벤트)을 연다\n'
           '3. 실행기 기록에서 그 작업의 시작 신호([CLI-INIT])를 찾는다',
     expect='1. 실행 상태가 completed 다\n'
            '2. AGENT_CALL 단계 이벤트에 앱 도구 호출이 1회 이상 남는다\n'
            '3. 그 작업의 [CLI-INIT] 에서 aimbase-server 상태가 connected 다',
     judge=['서버', '서버', '서버'],
     src=['CR-152'], pri='High'),

dict(id='IT-01-002', flow='CLI 작업 시작', kind='정상', title='실행기가 CLI 에 앱 도구 연결 대기 설정을 넘긴다',
     pre='cli-runner 단위시험(가짜 CLI 가 시작 신호를 낸다)',
     steps='1. 앱 도구 서버(aimbase-server)를 넘기는 워커를 띄우고 첫 턴을 보낸다\n'
           '2. 앱 도구 서버를 넘기지 않는 워커를 띄우고 첫 턴을 보낸다',
     expect='1. CLI 프로세스가 MCP_CONNECTION_NONBLOCKING=false 와 연결 대기 상한(밀리초)을 받는다\n'
            '2. 시작 신호에서 aimbase-server 가 connected 면 그 턴이 답을 낸다\n'
            '3. 앱 도구 서버를 넘기지 않는 워커는 두 값을 바꾸지 않고 시험 프로세스의 값을 그대로 넘긴다',
     judge=['단위', '단위', '단위'],
     pair={1: [1], 2: [1], 3: [2]},
     src=['CR-152'], pri='High'),

dict(id='IT-01-003', flow='CLI 작업 시작', kind='예외', title='앱 도구 연결이 안 된 채 첫 턴이 오면 그 단계가 실패로 끝난다',
     pre='cli-runner 단위시험(가짜 CLI 가 시작 신호를 낸다)',
     steps='1. 시작 신호에서 aimbase-server 가 pending 인 워커에 첫 턴을 보낸다\n'
           '2. 시작 신호 목록에 aimbase-server 가 없는 워커에 첫 턴을 보낸다\n'
           '3. NATIVE 모드에 스킬을 지정하고 시작 신호에서 aimbase-skills 가 pending 인 워커에 첫 턴을 보낸다',
     expect='1. 그 턴이 답 없이 "aimbase-server=pending" 을 담은 실패로 끝난다\n'
            '2. 그 턴이 답 없이 "aimbase-server=missing" 을 담은 실패로 끝난다\n'
            '3. 그 턴이 "aimbase-skills=pending" 을 담은 실패로 끝나고 실패 문구에 aimbase-server 가 없다',
     judge=['단위', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3]},
     src=['CR-152'], pri='High'),

dict(id='IT-01-004', flow='큰 도구 결과', kind='정상', title='8,000자를 넘는 앱 도구 결과가 잘리지 않고 CLI 단계에 닿는다',
     pre='결과가 8,000자를 넘는 앱 도구와 그 도구를 부르는 CLI 단계가 있는 워크플로우 1개(예: 입찰대행 proposal-design 의 get_proposal_design_input, 81,881자)',
     steps='1. 그 워크플로우를 실행한다\n'
           '2. 실행이 끝나면 워크플로우 실행 기록에서 그 도구 호출의 결과 본문과 단계 결과문을 연다',
     expect='1. 그 도구 호출 결과 본문의 글자 수가 앱이 낸 결과 글자 수(output_size)와 같다\n'
            '2. 결과 본문에 "결과 축약" 생략 안내가 없다\n'
            '3. 단계 결과문에 결과가 잘렸다는 설명(truncated)이 없다',
     judge=['서버', '서버', '서버'],
     src=['CR-153'], pri='High'),

dict(id='IT-01-005', flow='큰 도구 결과', kind='정상', title='CLI 연결 도구 목록의 도구마다 결과 크기 상한이 실린다',
     pre='테넌트에 묶인 API 키 1개, 런타임 설정 orchestrator.mcp-tool-result-max-chars 를 바꾸지 않은 상태(기본 200000)',
     steps='1. POST /mcp 에 X-API-Key 머리글로 initialize 를 보내고 받은 세션으로 tools/list 를 보낸다\n'
           '2. POST /mcp-skills 에도 같은 순서로 보낸다',
     expect='1. /mcp 도구 목록의 도구 전부가 _meta 의 anthropic/maxResultSizeChars 에 200000 을 싣는다\n'
            '2. /mcp-skills 도구 목록의 도구 전부가 _meta 의 anthropic/maxResultSizeChars 에 200000 을 싣는다',
     judge=['서버', '서버'],
     src=['CR-153'], pri='High'),

dict(id='IT-01-006', flow='큰 도구 결과', kind='정상', title='런타임 설정에서 상한을 바꾸면 두 통로 도구 목록이 새 값으로 다시 실린다',
     pre='Super Admin 계정, 테넌트에 묶인 API 키 1개, 다른 사람이 그 설정을 쓰지 않는 시험 서버',
     steps='1. 사이드바 런타임 설정 화면의 Orchestrator 카드에서 orchestrator.mcp-tool-result-max-chars 를 300000 으로 바꿔 저장한다\n'
           '2. 30초 뒤 /mcp 와 /mcp-skills 의 tools/list 를 다시 받는다\n'
           '3. 값을 바꾸기 전 값으로 되돌려 저장한다',
     expect='1. 저장 뒤 Orchestrator 카드의 그 설정 칸에 300000 이 보인다\n'
            '2. /mcp 도구 목록의 도구 전부가 anthropic/maxResultSizeChars 에 300000 을 싣는다\n'
            '3. /mcp-skills 도구 목록의 도구 전부가 anthropic/maxResultSizeChars 에 300000 을 싣는다',
     judge=['합동', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [2]},
     src=['CR-153'], pri='Medium'),

dict(id='IT-01-007', flow='큰 도구 결과', kind='예외', title='상한 설정이 없거나 0 이하면 기본값 200000 을 싣는다',
     pre='platform-core 단위시험(설정 서비스를 흉내 낸다)',
     steps='1. 설정이 없는 상태, 0, -1 각각으로 도구 목록을 싣는다',
     expect='1. 세 경우 모두 두 통로 도구 전부가 anthropic/maxResultSizeChars 에 200000 을 싣는다',
     judge=['단위'],
     src=['CR-153'], pri='Medium'),

dict(id='IT-01-008', flow='큰 도구 결과', kind='정상', title='도구 결과를 넘기는 두 MCP 서버가 결과를 자르지 않는다',
     pre='platform-core, tool-sdk-mcp 단위시험',
     steps='1. 서버 MCP 통로로 81,881자를 돌려주는 도구를 부른다\n'
           '2. 실행기의 MCP 서버(Tool SDK)로 81,881자를 돌려주는 도구를 부른다',
     expect='1. 서버 MCP 통로 응답 본문에 81,881자 결과가 그대로 있다\n'
            '2. 실행기 MCP 서버 응답 본문에 81,881자 결과가 그대로 있다',
     judge=['단위', '단위'],
     pair={1: [1], 2: [2]},
     src=['CR-153'], pri='High'),

dict(id='IT-01-009', flow='앱 도구 서버 키', kind='정상', title='실행기 CLI 의 앱 도구 서버 접속 기록에 서버 API 키가 남지 않는다',
     pre='앱 도구 서버(MCP)가 붙은 CLI 연결과 그 연결로 앱 도구를 부르는 AGENT_CALL 단계가 있는 워크플로우 1개, 실행기 컨테이너에 들어갈 수 있는 서버 계정',
     steps='1. 실행기(aimbase-agent)를 다시 띄운 뒤 그 워크플로우를 실행한다\n'
           '2. 실행기 컨테이너의 ~/.cache/claude-cli-nodejs/<작업 폴더>/mcp-logs-aimbase-server/ 에서 실행 뒤 생긴 기록 파일을 연다(워크플로우는 -app, 채팅은 -data-workspace-chat-<세션>)',
     expect='1. 실행 상태가 completed 고 AGENT_CALL 단계 이벤트에 앱 도구 호출이 1회 이상 남는다\n'
            '2. 기록의 "HTTP transport options" 줄 머리글에서 Authorization 값이 [REDACTED] 다\n'
            '3. 기록 폴더의 어느 파일에도 X-API-Key 머리글과 서버 API 키 원문이 없다\n'
            '4. 실행기 컨테이너 기록의 "ClaudeCliWorker start" 줄에 서버 API 키 원문이 없다',
     judge=['서버', '서버', '서버', '서버'],
     src=['CR-154'], pri='High'),

dict(id='IT-01-010', flow='앱 도구 서버 키', kind='정상', title='서버가 Authorization: ApiKey 머리글의 키로 인증하고 실행기는 그 머리글로만 키를 싣는다',
     pre='platform-core, cli-runner 단위시험(API 키 저장소를 흉내 낸다)',
     steps='1. Authorization: ApiKey <테넌트 키> 로 요청한다\n'
           '2. Authorization: apikey <테넌트 키> 로 요청한다\n'
           '3. Authorization: Bearer <키 문자열> 로 요청한다\n'
           '4. X-API-Key: <테넌트 키> 로 요청한다\n'
           '5. 실행기에 서버 MCP 주소와 키를 설정하고 CLI 에 넘길 MCP 설정을 만든다\n'
           '6. X-API-Key 없이 Authorization: ApiKey <키> 로 위젯 토큰 발급을 요청한다\n'
           '7. 머리글에 키, 환경변수에 토큰이 든 MCP 설정으로 실행기가 CLI 시작 기록을 남긴다',
     expect='1. 요청이 인증되고 테넌트가 그 키의 테넌트로 정해진다\n'
            '2. 스킴을 소문자로 써도 1 과 같이 인증된다\n'
            '3. 키를 조회하지 않고 인증 없이 다음 단계로 넘어간다\n'
            '4. X-API-Key 요청도 1 과 같이 인증된다\n'
            '5. aimbase-server 항목 머리글에 Authorization: ApiKey <키> 가 있고 X-API-Key 가 없다\n'
            '6. 위젯 토큰 발급이 200 으로 토큰을 돌려준다\n'
            '7. 시작 기록의 MCP 설정에서 머리글과 환경변수 값이 [REDACTED] 로 바뀌고 키와 토큰 원문이 없다',
     judge=['단위', '단위', '단위', '단위', '단위', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6], 7: [7]},
     src=['CR-154'], pri='High'),

dict(id='IT-01-011', flow='앱 도구 서버 키', kind='예외', title='운영 서버가 저장소에 적힌 키와 서명값을 쓰지 않는다',
     pre='저장소 최신본, 두 운영 서버(옛 서버, 우진비드 AWS)에 들어갈 수 있는 계정',
     steps='1. 실행기 키 값을 주지 않고 운영 compose(docker-compose.prod.yml)를 docker compose config 로 읽는다\n'
           '2. 두 서버의 api, rag-pipeline, 실행기 컨테이너 환경값을 SHA-256 앞 8자리로 뽑아 저장소 파일(compose, .env 견본)의 값과 대조한다',
     expect='1. compose 가 실행기 키 변수 하나를 들어 "<변수 이름> 값이 .env 에 없습니다" 로 멈추고 설정을 내지 않는다\n'
            '2. 컨테이너 환경값 가운데 저장소 파일 값과 앞 8자리가 같은 것은 읽는 코드가 없는 ENCRYPTION_KEY 뿐이다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     src=['CR-155'], pri='High'),

dict(id='IT-01-012', flow='실행기 등록', kind='예외', title='실행기 등록은 그 테넌트 키로만 되고, api 보다 먼저 떠도 등록된다',
     pre='운영 서버 하나(옛 서버 또는 우진비드 AWS), 그 서버 실행기 1개의 이름과 테넌트',
     steps='1. 키 없이 그 테넌트로 실행기 등록(POST /api/v1/agents/register)을 보낸다\n'
           '2. 실행기를 멈추고 api 를 다시 띄우는 동안 실행기를 먼저 띄운다\n'
           '3. api 가 뜬 뒤 2분 기다려 그 테넌트의 실행기 등록 목록과 실행기 기록을 본다',
     expect='1. 등록 요청이 403 이고 그 테넌트 등록 목록에 새 줄이 생기지 않는다\n'
            '2. 실행기가 api 가 뜬 뒤 1분 안에 다시 등록해 ACTIVE 로 보이고, 실행기 기록에 상태 알림 400 이 없다\n'
            '3. 실행기는 자기 테넌트에 등록되고 다른 테넌트 등록 목록에 그 실행기 이름의 새 줄이 없다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     src=['CR-156'], pri='High'),

dict(id='IT-01-013', flow='실행기 동시 실행', kind='예외', title='대기 CLI 가 CPU 수의 절반보다 많이 쌓여도 새 실행이 시작한 그 초에 첫 출력을 받는다',
     pre='cli-runner 단위시험(가짜 CLI), 개발기 실행기 1개와 그 실행기로 도는 CLI 워크플로우 1개, 그 실행기의 CPU 수',
     steps='1. 가짜 CLI 워커 하나를 띄운다\n'
           '2. 워크플로우를 다섯 건씩 한 회차로 실행하고, 앞 회차 CLI 를 닫지 않은 채 회차 사이를 150초 쉬며 대기 CLI 가 CPU 수의 절반보다 많아질 때까지 되풀이한다\n'
           '3. 이어서 다섯 건을 한 회차로 실행하고 실행기 기록과 워크플로우 실행 기록을 본다',
     expect='1. 그 워커의 출력 읽기 둘(cli-worker-stdout, cli-worker-stderr)이 가상 스레드가 아닌 데몬 스레드로 뜬다\n'
            '2. 마지막 회차 다섯 건 모두 실행기 기록에서 시작한 초와 같은 초에 첫 출력([CLI-INIT])이 남는다\n'
            '3. 마지막 회차 다섯 건의 걸린 시간이 앞 회차들의 가장 긴 시간의 2배를 넘지 않는다',
     judge=['단위', '서버', '서버'],
     pair={1: [1], 2: [3], 3: [3]},
     src=['CR-163'], pri='High'),

dict(id='IT-01-014', flow='실행기 연결 신호 요청 한도', kind='예외', title='테넌트 분당 요청 한도를 업무 요청이 다 써도 실행기는 끊김으로 바뀌지 않고 업무 요청만 거절된다',
     pre='개발기 시험 테넌트의 API 키, 그 테넌트에 ACTIVE 실행기 1개, master DB 에서 그 테넌트 subscriptions.api_rpm_limit 을 20 으로 낮춘 상태(끝나면 되돌린다)',
     steps='1. 실행기 목록(GET /api/v1/agents)에서 그 실행기의 상태와 마지막 상태 알림 시각을 본다\n'
           '2. 6분 동안 매 초 업무 요청(GET /api/v1/workflows)을 보내 매 분 칸을 다 쓴다. 응답 코드를 센다\n'
           '3. 그동안 1분마다 1을 되풀이하고, 끝나면 실행기 기록에서 상태 알림과 등록의 429 를 센다',
     expect='1. ACTIVE 다\n'
            '2. 매 분 21번째 업무 요청부터 429 이고 Retry-After 가 붙는다\n'
            '3. 6분 내내 ACTIVE 고 마지막 상태 알림 시각이 1분마다 바뀐다. 실행기 기록의 상태 알림, 등록 429 는 0번이다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-141'], src=['CR-168'], pri='High'),

dict(id='IT-01-015', flow='실행기 연결 신호 요청 한도', kind='정상', title='운영 chaws 반영 뒤 bp_wms 실행기가 ACTIVE 로 남고 연결 신호 거절이 없다',
     pre='운영 chaws 에 CR-168 api 반영, 운영 DB 읽기 전용 접속',
     steps='1. 반영 뒤 30분 동안 aimbase_bp_wms 의 실행기 등록 상태와 마지막 상태 알림 시각을 10분마다 읽는다\n'
           '2. 같은 동안 운영 실행기 기록에서 상태 알림과 등록의 429 를 센다',
     expect='1. 세 번 모두 ACTIVE 고 마지막 상태 알림 시각이 2분 안이다\n'
            '2. 0번이다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['BIZ-141'], src=['CR-168'], pri='High'),


dict(id='IT-01-016', flow='실행기 연결 신호 요청 한도', kind='예외', title='한 테넌트의 연결 신호가 한 분에 상한(60)을 넘으면 넘는 것부터 429 를 받고 업무 요청 한도는 쓰지 않는다',
     pre='개발기 시험 테넌트의 API 키, 그 테넌트에 등록된 실행기 id 1개, master global_config 의 ratelimit.agent-signal-max-per-minute 가 60',
     steps='1. 한 분 안에 그 실행기 id 로 상태 알림(POST /api/v1/agents/{id}/heartbeat)을 70번 보내고 응답 코드를 센다\n'
           '2. 같은 분 안에 등록(POST /api/v1/agents/register)을 한 번 보낸다\n'
           '3. 같은 분 안에 업무 요청(GET /api/v1/workflows)을 한 번 보낸다',
     expect='1. 그 분에 이미 받은 연결 신호를 합쳐 60번째까지 200, 그 뒤는 429 와 Retry-After. 429 응답에 X-RateLimit-* 가 없고 본문이 Agent connection signal limit exceeded 다\n'
            '2. 429 다\n'
            '3. 200 이다. 연결 신호 70번이 테넌트 분당 요청 한도를 쓰지 않았다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-141'], src=['CR-168'], pri='High'),

dict(id='IT-01-017', flow='구독 분당 요청 한도', kind='정상', title='구독 변경 API 로 바꾼 분당 요청 한도가 재기동 없이 다음 요청부터 적용된다',
     pre='개발기 시험 테넌트의 API 키, 플랫폼 관리자 토큰, 그 테넌트의 지금 분당 요청 한도를 적어 둔 상태(끝나면 되돌린다)',
     steps='1. 구독을 변경한다(PUT /api/v1/platform/subscriptions/{tenantId}, 본문 {"apiRpmLimit":20})\n'
           '2. 그 테넌트 키로 업무 요청(GET /api/v1/workflows)을 한 번 보내고 응답 머리글을 본다\n'
           '3. 새 분이 시작되면 같은 분 안에 업무 요청을 25번 보내고 응답 코드를 센다\n'
           '4. 적어 둔 값으로 구독을 다시 변경하고 업무 요청을 한 번 보낸다',
     expect='1. 200 이고 응답의 apiRpmLimit 이 20 이다\n'
            '2. X-RateLimit-Limit 이 20 이다. api 를 재기동하지 않았다\n'
            '3. 20번째까지 200, 21번째부터 429 와 Retry-After 다\n'
            '4. 200 이고 X-RateLimit-Limit 이 적어 둔 값이다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-087', 'BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-018', flow='구독 분당 요청 한도', kind='정상', title='분당 요청 한도 0 은 무제한으로 저장되고 그 테넌트 요청은 세지 않고 통과한다',
     pre='개발기 시험 테넌트의 API 키, 플랫폼 관리자 토큰, 그 테넌트의 지금 분당 요청 한도를 적어 둔 상태(끝나면 되돌린다)',
     steps='1. 구독을 변경한다(본문 {"apiRpmLimit":0})\n'
           '2. 새 분이 시작되면 같은 분 안에 업무 요청을 적어 둔 한도보다 많이 보내고 응답 코드를 센다\n'
           '3. 적어 둔 값으로 구독을 다시 변경한다',
     expect='1. 200 이고 응답의 apiRpmLimit 이 0 이다\n'
            '2. 모두 200 이고 X-RateLimit-Limit 이 0 이다\n'
            '3. 200 이고 응답의 apiRpmLimit 이 적어 둔 값이다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-087', 'BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-019', flow='구독 분당 요청 한도', kind='예외', title='음수, 소수, 정수 범위 밖, 숫자가 아닌 값은 400 으로 거절되고 아무 값도 저장되지 않는다',
     pre='개발기 시험 테넌트, 플랫폼 관리자 토큰, 그 테넌트의 지금 분당 요청 한도와 최대 연결 수를 적어 둔 상태',
     steps='1. 구독을 변경한다(본문 {"apiRpmLimit":-1})\n'
           '2. 구독을 변경한다(본문 {"apiRpmLimit":1.5})\n'
           '3. 구독을 변경한다(본문 {"apiRpmLimit":2147483648})\n'
           '4. 구독을 변경한다(본문 {"apiRpmLimit":"abc"})\n'
           '5. 구독을 변경한다(본문 {"maxConnections":적어 둔 값 + 1,"apiRpmLimit":-1})\n'
           '6. 구독 목록(GET /api/v1/platform/subscriptions)에서 그 테넌트를 본다',
     expect='1. 400 이고 본문 error 가 apiRpmLimit must be an integer of 0 or greater. 다\n'
            '2. 400 이고 본문 error 가 같다\n'
            '3. 400 이고 본문 error 가 같다\n'
            '4. 400 이다\n'
            '5. 400 이다\n'
            '6. 분당 요청 한도와 최대 연결 수가 적어 둔 값 그대로다',
     judge=['서버', '서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6]},
     req=['BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-020', flow='구독 분당 요청 한도', kind='정상', title='분당 요청 한도를 보내지 않은 구독 변경은 그 값을 그대로 둔다',
     pre='개발기 시험 테넌트, 플랫폼 관리자 토큰, 그 테넌트의 지금 분당 요청 한도와 최대 연결 수를 적어 둔 상태(끝나면 되돌린다)',
     steps='1. 구독을 변경한다(본문 {"maxConnections":적어 둔 값 + 1})\n'
           '2. 적어 둔 최대 연결 수로 구독을 다시 변경한다',
     expect='1. 200 이고 최대 연결 수는 바뀌고 apiRpmLimit 은 적어 둔 값 그대로다\n'
            '2. 200 이고 apiRpmLimit 은 적어 둔 값 그대로다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-021', flow='구독 관리 화면', kind='정상', title='구독 관리 표가 서버 값을 보이고 일일 요청 한도 칸이 없다',
     pre='개발기 화면에 플랫폼 관리자로 로그인, 분당 요청 한도가 0 인 테넌트와 1 이상인 테넌트가 있는 상태',
     steps='1. 왼쪽 메뉴에서 구독 관리를 연다\n'
           '2. 표의 칸 제목을 읽는다\n'
           '3. 구독 목록 API 값과 표의 월간 토큰 한도, 분당 요청 한도, 최대 연결, 만료일을 견준다\n'
           '4. 분당 요청 한도가 0 인 테넌트의 그 칸을 본다',
     expect='1. 표가 보인다\n'
            '2. 테넌트, 플랜, 월간 토큰 한도, 분당 요청 한도, 최대 연결, 만료일, 액션이고 일일 요청 한도는 없다\n'
            '3. 모두 같다. 만료일 값이 없는 테넌트는 - 로 보인다\n'
            '4. 무제한으로 보인다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-087', 'BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-022', flow='구독 관리 화면', kind='정상', title='편집 창에서 분당 요청 한도와 월간 토큰 한도를 바꾸면 저장되어 표에 보이고 다음 요청부터 적용된다',
     pre='개발기 화면에 플랫폼 관리자로 로그인, 시험 테넌트의 지금 값을 적어 둔 상태(끝나면 되돌린다)',
     steps='1. 그 테넌트 줄의 편집을 누른다\n'
           '2. 분당 요청 한도 칸 아래 안내를 읽는다\n'
           '3. 분당 요청 한도와 월간 토큰 한도를 다른 값으로 바꾸고 저장을 누른다\n'
           '4. 그 테넌트 키로 업무 요청을 보내 응답 머리글을 본다\n'
           '5. 구독 목록 API 에서 그 테넌트의 플랜과 나머지 값을 본다\n'
           '6. 적어 둔 값으로 되돌린다',
     expect='1. 표 아래 편집 창에 월간 토큰 한도, 분당 요청 한도, 최대 연결 수가 서버 값으로 보인다\n'
            '2. 저장하면 다음 요청부터 적용됩니다. 0 은 무제한입니다. 로 보인다\n'
            '3. 창이 닫히고 표의 두 칸이 새 값으로 보인다\n'
            '4. X-RateLimit-Limit 이 새 값이다\n'
            '5. 바꾸지 않은 값은 그대로다\n'
            '6. 표와 API 값이 적어 둔 값이다',
     judge=['합동', '합동', '합동', '서버', '서버', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6]},
     req=['PRD-087', 'BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-023', flow='구독 관리 화면', kind='예외', title='편집 창의 빈 칸, 음수, 소수는 문구를 보이고 저장을 막는다',
     pre='개발기 화면에 플랫폼 관리자로 로그인, 시험 테넌트의 편집 창을 연 상태',
     steps='1. 분당 요청 한도 칸을 비운다\n'
           '2. 분당 요청 한도에 -1 을 넣는다\n'
           '3. 분당 요청 한도에 1.5 를 넣는다\n'
           '4. 월간 토큰 한도 칸을 비운다\n'
           '5. 최대 연결 수 칸을 비운다\n'
           '6. 세 칸에 0 을 넣는다',
     expect='1. 그 칸 아래 0 이상의 정수를 입력하세요. 가 보이고 저장 단추가 막힌다\n'
            '2. 같다\n'
            '3. 같다\n'
            '4. 같다\n'
            '5. 같다\n'
            '6. 문구가 없어지고 저장 단추가 살아난다. 누르지 않고 취소한다',
     judge=['합동', '합동', '합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6]},
     req=['BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-024', flow='구독 관리 화면', kind='예외', title='저장이 실패하면 창을 닫지 않고 서버 문구를 표시한다',
     pre='개발기 화면에 플랫폼 관리자로 로그인, 시험 테넌트의 편집 창을 연 상태, 브라우저에서 저장 요청의 응답을 400 으로 바꿔 줄 수 있는 상태',
     steps='1. 분당 요청 한도를 바꾸고 저장을 누른다\n'
           '2. 브라우저에서 응답 바꾸기를 끄고 취소를 누른 뒤 표를 본다',
     expect='1. 창이 그대로 있고 입력값이 남아 있으며 저장하지 못했습니다: 뒤에 서버 문구가 보인다\n'
            '2. 표의 값은 바뀌지 않았다',
     judge=['합동', '합동'],
     pair={1: [1], 2: [2]},
     req=['BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-025', flow='구독 분당 요청 한도', kind='정상', title='운영 chaws 반영 뒤 구독 조회 API 와 읽기 전용 DB 에서 bp_wms 분당 요청 한도 600 이 보인다',
     pre='운영 chaws 에 CR-169 api 반영, 운영 DB 읽기 전용 접속, 서버의 secrets/admin-password. 값은 바꾸지 않는다',
     steps='1. 서버 안에서 관리자로 로그인해 구독 목록(GET /api/v1/platform/subscriptions)을 읽는다\n'
           '2. master DB 를 읽기 전용 트랜잭션으로 subscriptions 의 bp_wms 를 읽는다\n'
           '3. deployed.log 와 api 상태를 본다',
     expect='1. bp_wms 의 apiRpmLimit 이 600 이다\n'
            '2. api_rpm_limit 이 600 이고 updated_at 이 반영 전과 같다\n'
            '3. 새 커밋이 기록되어 있고 api 가 healthy 다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-142'], src=['CR-169'], pri='Medium'),

dict(id='IT-01-026', flow='CLI 프로세스 정리', kind='정상', title='세션 없이 온 요청은 답한 뒤 그 요청의 CLI 프로세스를 닫고, 세션이 있는 요청은 남긴다',
     pre='platform-core 단위시험(CLI 어댑터를 흉내 낸다)',
     steps='1. 세션 번호 없이 일반 요청과 스트림 요청을 하나씩 보낸다\n'
           '2. 세션 번호를 붙여 일반 요청과 스트림 요청을 하나씩 보낸다\n'
           '3. 세션 번호 없이 보낸 요청이 실행기 오류로 실패하게 한다\n'
           '4. 같은 질문이 응답 캐시에 있는 상태에서 세션 번호 없이 보낸다',
     expect='1. 두 요청 모두 답한 뒤 그 요청에 붙인 세션 번호로 프로세스 닫기를 한 번 부른다\n'
            '2. 프로세스 닫기를 부르지 않는다\n'
            '3. 실패해도 프로세스 닫기를 부른다\n'
            '4. CLI 를 부르지 않고 프로세스 닫기도 부르지 않는다',
     judge=['단위', '단위', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     src=['CR-173'], pri='High'),

dict(id='IT-01-027', flow='CLI 프로세스 정리', kind='정상', title='CS 실행기로 세션 없는 요청을 보내면 답한 그 초에 실행기 프로세스가 닫힌다',
     pre='개발기(59) 또는 운영 chaws 의 bp_wes 테넌트 키(서버 안에서만 쓴다), CS 실행기 연결 cli-runner-bp-wes-cs-work01',
     steps='1. 서버 안에서 session_id 없이 stream false 로 채팅 완성(POST /api/v1/chat/completions)을 한 번 보낸다\n'
           '2. CS 실행기 기록에서 그 응답의 session_id 줄을 찾는다\n'
           '3. CS 실행기 컨테이너 안의 claude 프로세스 수를 요청 전과 비교한다',
     expect='1. 200 이고 답이 온다\n'
            '2. 같은 run 의 main worker spawned 뒤 같은 초에 shutdown complete, closed 1 workers 가 있다\n'
            '3. 요청 전보다 늘지 않는다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     src=['CR-173'], pri='High'),

dict(id='IT-01-028', flow='키 없는 서버의 임베딩', kind='예외', title='OpenAI 키가 비어 있는 서버는 임베딩을 만들지 않아 채팅마다 401 경고를 남기지 않는다',
     pre='platform-core 단위시험, 운영 chaws api(OpenAI 키를 두지 않는다)',
     steps='1. OpenAI 키를 빈 값, 공백, 설정 없음, 값 있음으로 두고 서버 설정을 올린다\n'
           '2. 임베딩이 없는 상태로 응답 캐시를 조회하고 저장한다\n'
           '3. 운영 chaws 에서 세션 없는 채팅 한 번 뒤 api 기록을 본다',
     expect='1. 빈 값, 공백, 설정 없음이면 임베딩을 만들지 않고, 값이 있으면 만든다\n'
            '2. 같은 질문 찾기만 하고 임베딩을 부르지 않는다\n'
            '3. Retry error 와 401 경고가 0건이다',
     judge=['단위', '단위', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     src=['CR-173'], pri='Medium'),


# IT-01-029, 030 의 근거는 CR-173 문서 14절(실행기 전체 Claude 프로세스 상한과 줄서기)이다.
dict(id='IT-01-029', flow='CLI 프로세스 정리', kind='정상', title='실행기 전체 Claude 프로세스 상한을 넘는 요청은 줄을 서고, 쉬는 프로세스를 닫아 자리를 낸다',
     pre='cli-runner, aimbase-agent 단위시험(가짜 CLI). 상한 1 또는 2',
     steps='1. 상한만큼 다른 run 이 프로세스를 쥔 채 새 run 을 부른다\n'
           '2. 1 에서 대기 상한을 짧게 두고 아무도 놓지 않는다\n'
           '3. 답을 마치고 쉬는 run 이 자리를 쥔 채 새 run 을 부른다\n'
           '4. 같은 run 을 다시 부른다\n'
           '5. 프로세스가 죽은 run 을 다시 부른다\n'
           '6. 서로 다른 run 14개를 한꺼번에 부른다(상한 2)\n'
           '7. 자리 내기로 닫힌 run 이 다음 턴을 보낸다',
     expect='1. 앞 run 이 닫히면 그때 프로세스를 띄운다\n'
            '2. 대기 상한에서 실패로 돌려주고 자리를 남기지 않는다\n'
            '3. 쉬는 프로세스를 닫고 새 run 이 그 자리를 쓴다\n'
            '4. 같은 프로세스를 쓰고 자리를 더 쓰지 않는다\n'
            '5. 죽은 프로세스의 자리를 돌려받아 새로 띄운다\n'
            '6. 어느 순간에도 살아 있는 프로세스가 2 를 넘지 않고 14개 모두 답한다\n'
            '7. 새 프로세스에 대화 전체를 다시 넣는다',
     judge=['단위', '단위', '단위', '단위', '단위', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6], 7: [7]},
     src=['CR-173'], pri='High'),

dict(id='IT-01-030', flow='CLI 프로세스 정리', kind='정상', title='CS 묶음으로 세션 없는 요청 14건이 한꺼번에 와도 CS 실행기의 Claude 프로세스와 메모리가 상한 안에 머문다',
     pre='개발기(59) 또는 운영 chaws, CS 실행기 AIMBASE_RUNNER_MAX_PROCESSES 2, 묶음 cli-runner-bp-wes-cs-rr, bp_wes 소비앱 키(서버 안에서만 쓴다)',
     steps='1. 서버 안에서 session_id 없이 stream false 로 문장이 서로 다른 짧은 요청 14건을 한꺼번에 보낸다(같은 문장은 응답 캐시가 답한다)\n'
           '2. 그동안 CS 실행기 컨테이너 안에서 java 가 띄운 claude 프로세스 수를 0.2초마다 센다\n'
           '3. 그동안 CS 실행기 메모리를 잰다\n'
           '4. 끝난 뒤 실행기 기록의 생성, 요청 뒤 닫힘, 대기 초과 줄을 센다',
     expect='1. 14건 모두 200 이고 답이 온다\n'
            '2. 2 를 넘지 않는다(막 뜬 claude 가 1초 안 되게 낳는 자기 자식은 세지 않는다)\n'
            '3. 운영 한도 1.5GiB 보다 작다\n'
            '4. 생성 수와 요청 뒤 닫힘 수가 같고 대기 초과는 0 이다. 끝난 뒤 claude 는 0 이다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     src=['CR-173'], pri='High'),

dict(id='IT-01-031', flow='플랜 분당 요청 한도', kind='정상', title='플랜 없이 만든 테넌트와 플랜을 고른 테넌트가 그 플랜의 분당 요청 한도로 시작한다',
     pre='로컬 api, 플랫폼 관리자 토큰, 시험용 테넌트 두 개를 만들 수 있는 상태(끝나면 지운다)',
     steps='1. 플랜 목록(GET /api/v1/platform/plans)을 읽는다\n'
           '2. 플랜 없이 테넌트를 만든다(POST /api/v1/platform/tenants)\n'
           '3. 플랜 pro 로 테넌트를 만든다\n'
           '4. 구독 목록(GET /api/v1/platform/subscriptions)에서 두 테넌트를 본다\n'
           '5. 두 시험 테넌트를 지운다',
     expect='1. 200 이고 defaultPlan 이 free, plans 가 free, starter, pro, enterprise 순이며 한도가 60, 300, 1000, 0 이다\n'
            '2. 생성이 성공한다\n'
            '3. 생성이 성공한다\n'
            '4. 첫 테넌트는 free 와 60, 둘째는 pro 와 1000 이다\n'
            '5. 두 테넌트가 목록에서 없어진다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-080', 'PRD-376', 'BIZ-153'], src=['CR-191'], pri='High'),

dict(id='IT-01-032', flow='플랜 분당 요청 한도', kind='예외', title='목록에 없는 플랜으로 테넌트를 만들면 400 이고 테넌트와 DB 가 생기지 않는다',
     pre='로컬 api, 플랫폼 관리자 토큰',
     steps='1. 플랜 standard 로 테넌트를 만든다\n'
           '2. 테넌트 목록과 DB 목록에서 그 테넌트 ID 를 찾는다',
     expect='1. 400 이고 본문 error 가 plan must be one of: free, starter, pro, enterprise 다\n'
            '2. 테넌트 행도 aimbase_<ID> DB 도 없다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-080', 'BIZ-153'], src=['CR-191'], pri='High'),

dict(id='IT-01-033', flow='플랜 분당 요청 한도', kind='정상', title='구독 변경으로 다른 플랜으로 바꾸면 한도도 그 플랜의 값이 되고, 같은 플랜이면 그대로, 한도를 같이 보내면 보낸 값이다',
     pre='개발기 시험 테넌트, 플랫폼 관리자 토큰, 그 테넌트의 지금 플랜과 분당 요청 한도를 적어 둔 상태(끝나면 되돌린다)',
     steps='1. 지금과 다른 플랜 starter 로 구독을 변경한다(본문 {"plan":"starter"})\n'
           '2. 그 테넌트 키로 업무 요청(GET /api/v1/workflows)을 한 번 보내고 응답 머리글을 본다\n'
           '3. 한도만 77 로 바꾼 뒤(본문 {"apiRpmLimit":77}) 같은 플랜으로 다시 변경한다(본문 {"plan":"starter"})\n'
           '4. 플랜과 한도를 같이 보낸다(본문 {"plan":"pro","apiRpmLimit":250})\n'
           '5. 적어 둔 플랜과 한도로 되돌린다(본문 {"plan":적어 둔 플랜,"apiRpmLimit":적어 둔 한도})',
     expect='1. 200 이고 plan 이 starter, apiRpmLimit 이 300 이다\n'
            '2. X-RateLimit-Limit 이 300 이다. api 를 재기동하지 않았다\n'
            '3. 두 번 다 200 이고 apiRpmLimit 이 77 그대로다\n'
            '4. 200 이고 plan 이 pro, apiRpmLimit 이 250 이다\n'
            '5. 200 이고 적어 둔 값이다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-087', 'BIZ-153'], src=['CR-191'], pri='High'),

dict(id='IT-01-034', flow='플랜 분당 요청 한도', kind='예외', title='구독 변경에서 목록에 없는 플랜은 400 이고 같이 보낸 값도 저장되지 않는다',
     pre='개발기 시험 테넌트, 플랫폼 관리자 토큰, 그 테넌트의 지금 플랜, 분당 요청 한도, 최대 연결 수를 적어 둔 상태',
     steps='1. 구독을 변경한다(본문 {"plan":"standard","maxConnections":적어 둔 값 + 1})\n'
           '2. 구독 목록에서 그 테넌트를 본다',
     expect='1. 400 이고 본문 error 가 plan must be one of: free, starter, pro, enterprise 다\n'
            '2. 플랜, 분당 요청 한도, 최대 연결 수가 적어 둔 값 그대로다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-087', 'BIZ-153'], src=['CR-191'], pri='Medium'),

dict(id='IT-01-035', flow='플랜 분당 요청 한도', kind='정상', title='운영 설정에서 플랜 한도를 바꾸면 새로 만드는 테넌트부터 적용되고 이미 있는 테넌트는 그대로다',
     pre='로컬 api, 플랫폼 관리자 토큰, free 플랜 테넌트 하나의 한도를 적어 둔 상태(끝나면 설정을 60 으로 되돌리고 시험 테넌트를 지운다)',
     steps='1. 운영 설정을 저장한다(PUT /api/v1/platform/settings, plan.free.api-rpm-limit 45)\n'
           '2. 플랜 목록을 읽는다\n'
           '3. 플랜 없이 테넌트를 만들고 구독 목록에서 본다\n'
           '4. 적어 둔 free 테넌트를 구독 목록에서 본다\n'
           '5. plan.free.api-rpm-limit 를 60 으로 되돌리고 시험 테넌트를 지운다',
     expect='1. 200 이다\n'
            '2. free 의 apiRpmLimit 이 45 다\n'
            '3. 새 테넌트가 free 와 45 다\n'
            '4. 한도가 적어 둔 값 그대로다\n'
            '5. 플랜 목록의 free 가 60 이고 시험 테넌트가 없다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['BIZ-153'], src=['CR-191'], pri='Medium'),

dict(id='IT-01-036', flow='플랜 분당 요청 한도', kind='예외', title='운영 설정의 틀린 플랜 값은 400 이고 같은 요청의 다른 값도 저장되지 않는다',
     pre='로컬 api, 플랫폼 관리자 토큰, 플랜 설정의 지금 값을 적어 둔 상태',
     steps='1. plan.pro.api-rpm-limit 를 -1 로 저장한다\n'
           '2. plan.pro.api-rpm-limit 를 1.5 로 저장한다\n'
           '3. plan.default 를 standard 로 저장한다\n'
           '4. plan.codes 를 free,gold 로 저장한다\n'
           '5. 한 요청에 plan.starter.api-rpm-limit 301 과 plan.pro.api-rpm-limit -1 을 같이 저장한다\n'
           '6. 플랜 목록을 읽는다',
     expect='1. 400 이다\n'
            '2. 400 이다\n'
            '3. 400 이다\n'
            '4. 400 이다\n'
            '5. 400 이다\n'
            '6. 플랜 목록과 한도가 적어 둔 값 그대로다. starter 도 301 이 아니다',
     judge=['서버', '서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6]},
     req=['BIZ-153'], src=['CR-191'], pri='Medium'),

dict(id='IT-01-037', flow='플랜 화면', kind='정상', title='테넌트 생성 창의 플랜 선택지가 플랜 목록 순서이고 처음 선택이 기본 플랜이며, 표의 플랜 배지가 이름 그대로다',
     pre='로컬 또는 개발기 화면, 플랫폼 관리자 로그인',
     steps='1. 테넌트 관리에서 「새 테넌트」를 누르고 플랜 칸을 연다\n'
           '2. 테넌트 관리 표와 구독 관리 표의 플랜 칸을 본다\n'
           '3. 플랜 목록 응답을 실패로 바꾸고 「새 테넌트」를 누른다',
     expect='1. 선택지가 free, starter, pro, enterprise 순이고 처음 선택이 free 다. standard 가 없다\n'
            '2. 플랜 이름이 그대로 보이고 모든 플랜 배지가 같은 색이다\n'
            '3. 플랜 칸 아래 「플랜 목록을 읽지 못했습니다.」가 보이고 생성 단추가 막혀 있다',
     judge=['화면', '화면', '화면'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-080', 'PRD-376'], src=['CR-191'], pri='Medium'),

dict(id='IT-01-038', flow='플랜 화면', kind='정상', title='운영 설정에 「플랜」 카드가 보이고, 틀린 값은 저장 실패 문구와 함께 저장되지 않는다',
     pre='로컬 화면, 플랫폼 관리자 로그인(끝나면 바꾼 값을 되돌린다)',
     steps='1. 운영 설정을 연다\n'
           '2. 플랜 카드에서 plan.free.api-rpm-limit 를 -1 로 바꿔 저장한다\n'
           '3. 같은 칸을 61 로 바꿔 저장하고, 다시 60 으로 저장한다',
     expect='1. 「플랜」 카드에 plan.codes, plan.default, 플랜별 api-rpm-limit 넷이 보이고 카드 설명이 보인다\n'
            '2. 「저장하지 못했습니다: (서버 문구)」가 보이고 고친 값이 칸에 남는다. 플랜 목록의 free 는 그대로다\n'
            '3. 저장 실패 문구가 없어지고 저장됨이 잠깐 보인다. 플랜 목록의 free 가 61, 다음에 60 이다',
     judge=['화면', '화면', '화면'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-153'], src=['CR-191'], pri='Medium'),

dict(id='IT-01-039', flow='플랜 분당 요청 한도', kind='정상', title='반영 뒤 플랜 설정 6행이 생기고 standard 행이 starter 로 바뀌며 기존 테넌트의 한도는 그대로다',
     pre='개발기(59) 또는 운영 chaws 에 CR-191 api 반영, master DB 읽기 전용 접속, 반영 전 구독 표를 적어 둔 상태. 값은 바꾸지 않는다',
     steps='1. master DB 를 읽기 전용 트랜잭션으로 global_config 의 plan. 으로 시작하는 행을 읽는다\n'
           '2. subscriptions 의 tenant_id, plan, api_rpm_limit 을 읽어 반영 전과 비교한다\n'
           '3. 서버 안에서 관리자로 로그인해 플랜 목록(GET /api/v1/platform/plans)을 읽는다',
     expect='1. plan.codes, plan.default, plan.free/starter/pro/enterprise.api-rpm-limit 6행이 있다\n'
            '2. plan 이 standard 인 행이 없고, 한도는 모든 행이 반영 전과 같다(59 lexflow 300, chaws bp_wms 600 포함)\n'
            '3. 200 이고 1 의 값과 같다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-153'], src=['CR-191'], pri='High'),

dict(id='IT-01-040', flow='운영 배포 되돌리기 이미지 정리', kind='정상', title='운영 chaws 에 Aimbase 를 다시 올린 뒤 올린 이미지의 되돌리기용 pre- 태그가 최근 2개만 남고 다른 이미지와 컨테이너는 그대로다',
     pre='CR-201 이 들어간 dev 의 deploy.sh 로 하는 운영 chaws Aimbase 배포 한 번(chaws-be, chaws-agent 등 어느 것이든), PRE_IMAGE_KEEP 을 주지 않은 상태(기본 2). 배포 전 docker images, 컨테이너별 이미지 ID, docker volume ls 를 적어 둔다',
     steps='1. 배포 출력에서 [pre 정리] 로 시작하는 줄을 본다\n'
           '2. 배포 뒤 chaws 에서 올린 이미지의 pre-YYYYMMDD-HHMMSS 태그를 시각과 함께 본다\n'
           '3. 배포 뒤 컨테이너별 이미지 ID 를 배포 전 목록과 대조한다\n'
           '4. 배포 뒤 올리지 않은 이미지(운영 3PL bp-infra-*, 다른 aimbase-* 이미지)의 태그 목록을 배포 전과 대조한다\n'
           '5. 배포 뒤 docker volume ls 를 배포 전과 대조한다',
     expect='1. healthy 줄 뒤에 [pre 정리] 남김 또는 지움 줄이 나오고 못 지움 줄이 없다\n'
            '2. 올린 이미지의 pre- 태그가 2개 이하이고 남은 것이 시각이 가장 늦은 것들이다\n'
            '3. 다시 만든 컨테이너 말고는 모든 컨테이너가 배포 전과 같은 이미지 ID 로 떠 있다\n'
            '4. 올리지 않은 이미지의 태그 목록이 배포 전과 같다\n'
            '5. 볼륨 목록이 배포 전과 같다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=[], src=['CR-201'], pri='Medium'),

dict(id='IT-01-041', flow='채팅 분당 요청 한도', kind='정상', title='위젯 토큰 요청은 채팅 칸, 연동키와 로그인 토큰 요청과 위젯 토큰 발급은 테넌트 칸으로 센다',
     pre='로컬 또는 개발기 서버. 채팅 한도 5, 테넌트 한도 5 인 테넌트 하나, 그 테넌트의 연동키(X-API-Key)와 위젯 토큰(연동키로 발급), 테넌트 관리자 로그인 토큰. 실행기 연결(cli-runner) 하나',
     steps='1. 연동키로 워크플로우 목록 조회를 1번, 위젯 토큰 발급(POST /sessions/issue-widget-token)을 1번 부르고 응답 머리를 본다\n'
           '2. 로그인 토큰으로 대화 목록 조회를 1번 부르고 응답 머리를 본다\n'
           '3. 위젯 토큰으로 질문(POST /chat/completions), 지난 대화 목록, 선호 조회를 각 1번 부르고 응답 머리를 본다\n'
           '4. 실행기가 보내는 연결 신호(agent 신호)의 응답 머리를 본다\n'
           '5. Redis 의 rl: 으로 시작하는 키를 본다',
     expect='1. 둘 다 X-RateLimit-Scope 가 tenant 이고 X-RateLimit-Remaining 이 4, 3 으로 줄어든다\n'
            '2. X-RateLimit-Scope 가 tenant, Remaining 2\n'
            '3. 셋 다 X-RateLimit-Scope 가 chat 이고 Remaining 이 4, 3, 2 로 줄어든다. 테넌트 칸 Remaining 은 2 그대로다\n'
            '4. 연결 신호는 tenant 도 chat 도 아닌 전용 칸(BIZ-141)이라 두 칸의 Remaining 이 바뀌지 않는다\n'
            '5. rl:{테넌트}:{분} 과 rl:chat:{테넌트}:{분} 두 키가 따로 있고 값이 3 과 3 이다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-652', 'BIZ-297'], src=['CR-205'], pri='High'),

dict(id='IT-01-042', flow='채팅 분당 요청 한도', kind='정상', title='채팅이 몰려 채팅 칸을 다 써도 같은 테넌트의 연동키 요청(서면 초안)은 거절되지 않는다',
     pre='IT-01-041 과 같은 테넌트(채팅 한도 5, 테넌트 한도 300). 위젯 토큰 둘(사용자 둘)',
     steps='1. 두 위젯 토큰으로 같은 분 안에 질문을 번갈아 6번 보낸다(여섯째는 응답 도중 연결을 끊는다)\n'
           '2. 이어서 같은 분 안에 위젯 토큰으로 질문을 1번 더 보낸다\n'
           '3. 같은 분 안에 연동키로 워크플로우 실행(서면 초안)을 1번 부른다\n'
           '4. 다음 분에 위젯 토큰으로 질문을 1번 보낸다',
     expect='1. 다섯째까지 200 이고 여섯째는 429 다. 끊은 요청도 1 로 센다\n'
            '2. 429, X-RateLimit-Scope 가 chat\n'
            '3. 200(또는 실행 접수), X-RateLimit-Scope 가 tenant, X-RateLimit-Remaining 이 299\n'
            '4. 200, 채팅 칸 Remaining 이 4',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-652', 'BIZ-297'], src=['CR-205'], pri='High'),

dict(id='IT-01-043', flow='채팅 분당 요청 한도', kind='예외', title='채팅 칸을 넘으면 429 본문이 chat_rate_limited 이고, 테넌트 칸 429 본문은 그대로이며, 0 은 무제한이다',
     pre='채팅 한도 1, 테넌트 한도 1 인 테넌트와 그 위젯 토큰, 연동키',
     steps='1. 위젯 토큰으로 질문을 2번 보내고 둘째의 상태, 머리, 본문을 본다\n'
           '2. 연동키로 워크플로우 목록 조회를 2번 부르고 둘째의 상태, 머리, 본문을 본다\n'
           '3. 구독 변경 API 로 chatRpmLimit 을 0 으로 바꾸고 위젯 토큰으로 질문을 5번 보낸다',
     expect='1. 429, Retry-After 와 X-RateLimit-Scope: chat, 본문 success false, error 가 chat_rate_limited, data 에 limit 1, current 2, retry_after_seconds, scope chat\n'
            '2. 429, X-RateLimit-Scope: tenant, 본문 error 가 Rate limit exceeded. Max 1 requests per minute. 이고 data.scope 가 tenant\n'
            '3. 다섯 번 다 200 이고 응답 머리에 X-RateLimit-Limit 이 없다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-652', 'BIZ-297'], src=['CR-205'], pri='High'),

dict(id='IT-01-044', flow='채팅 분당 요청 한도', kind='정상', title='채팅 상한은 구독 행, 없으면 기본 플랜의 설정, 설정도 없으면 60 이다',
     pre='로컬 서버. 구독 행이 있는 테넌트 A(chat_rpm_limit 20), 구독 행이 없는 테넌트 B 와 각 위젯 토큰. plan.default 가 free',
     steps='1. A 의 위젯 토큰으로 질문을 1번 보내고 X-RateLimit-Limit 을 본다\n'
           '2. plan.free.chat-rpm-limit 을 30 으로 두고 B 의 위젯 토큰으로 질문을 1번 보낸다\n'
           '3. plan.free.chat-rpm-limit 행을 DB 에서 지우고 B 로 1번 더 보낸다(끝나면 60 으로 되살린다)',
     expect='1. X-RateLimit-Limit 20, Scope chat\n'
            '2. X-RateLimit-Limit 30\n'
            '3. X-RateLimit-Limit 60',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-297'], src=['CR-205'], pri='Medium'),

dict(id='IT-01-045', flow='채팅 분당 요청 한도', kind='정상', title='구독 변경 API 의 chatRpmLimit 은 다음 요청부터 적용되고, 틀린 값은 400, 플랜을 바꾸면 그 플랜의 값으로 바뀐다',
     pre='플랫폼 관리자 토큰. free 플랜(60, 채팅 60)인 테넌트와 그 위젯 토큰. plan.pro.chat-rpm-limit 1000',
     steps='1. PUT /platform/subscriptions/{tenantId} 에 {"chatRpmLimit": 7} 을 보내고 바로 위젯 토큰으로 질문을 1번 보낸다\n'
           '2. {"chatRpmLimit": -1}, {"chatRpmLimit": 1.5}, {"chatRpmLimit": "abc"}, {"maxConnections": 9, "chatRpmLimit": -1} 을 차례로 보낸다\n'
           '3. {"plan": "pro"} 를 보내고 구독을 조회한다\n'
           '4. {"plan": "pro"} 를 다시 보내고, 이어서 {"plan": "free", "chatRpmLimit": 120} 을 보내고 구독을 조회한다',
     expect='1. 200 이고 응답 본문 chatRpmLimit 7. 질문의 X-RateLimit-Limit 이 7 이다(재기동 없이)\n'
            '2. 넷 다 400 이고 error 가 chatRpmLimit must be an integer of 0 or greater. 이며 maxConnections 도 바뀌지 않았다\n'
            '3. apiRpmLimit 1000, chatRpmLimit 1000\n'
            '4. 같은 플랜은 그대로(1000). free 로 바꾸며 보낸 값이 이겨 apiRpmLimit 60, chatRpmLimit 120',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-087', 'BIZ-298'], src=['CR-205'], pri='High'),

dict(id='IT-01-046', flow='채팅 분당 요청 한도', kind='예외', title='플랜 목록에 chatRpmLimit 이 들고, 채팅 한도 설정 행이 없는 플랜 이름이나 음수는 플랜 설정 저장에서 거절된다',
     pre='플랫폼 관리자 토큰. 운영 설정 플랜 카드가 초기값(60, 300, 1000, 0)',
     steps='1. GET /platform/plans 를 부른다\n'
           '2. 운영 설정 저장 API 로 plan.codes 를 free,starter,pro,enterprise,gold 로 보낸다(gold 의 chat-rpm-limit 행은 없고 api-rpm-limit 행만 미리 넣어 둔다)\n'
           '3. plan.pro.chat-rpm-limit 을 -1 로 보낸다\n'
           '4. plan.pro.chat-rpm-limit 을 0 으로 보내고 다시 1000 으로 보낸다',
     expect='1. 200 이고 plans 의 모든 플랜에 apiRpmLimit 과 chatRpmLimit 이 있다. free 60/60, starter 300/300, pro 1000/1000, enterprise 0/0\n'
            '2. 400 이고 저장되지 않는다(plan.codes 가 그대로)\n'
            '3. 400 이고 저장되지 않는다\n'
            '4. 둘 다 200 이고 플랜 목록의 pro chatRpmLimit 이 0, 다음에 1000 이다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-376', 'BIZ-298'], src=['CR-205'], pri='Medium'),

dict(id='IT-01-047', flow='채팅 분당 요청 한도 화면', kind='정상', title='「구독 관리」 표와 편집 창에 「채팅 분당 요청 한도」 가 보이고 저장하면 바뀐다',
     pre='로컬 화면, 플랫폼 관리자 로그인. 채팅 한도 300 인 테넌트와 채팅 한도 0 인 테넌트(끝나면 바꾼 값을 되돌린다)',
     steps='1. 구독 관리를 연다\n'
           '2. 채팅 한도 300 인 테넌트의 편집 단추를 누른다\n'
           '3. 「채팅 분당 요청 한도」 를 120 으로 바꿔 저장한다\n'
           '4. 창 폭을 1024 로 줄여 표를 본다',
     expect='1. 표에 「분당 요청 한도」 다음에 「채팅 분당 요청 한도」 칸이 있고, 300 인 행은 300, 0 인 행은 「무제한」 으로 보인다\n'
            '2. 편집 창이 두 칸씩 두 줄이고 「채팅 분당 요청 한도」 에 300 이 있으며, 칸 아래 「채팅창(위젯 토큰) 요청만 따로 셉니다. 저장하면 다음 요청부터 적용됩니다. 0 은 무제한입니다.」 가 보인다\n'
            '3. 창이 닫히고 표의 그 행이 120 으로 바뀐다. 「분당 요청 한도」 는 그대로다\n'
            '4. 칸이 잘리지 않고 표가 가로로 넘치면 가로 스크롤이 생긴다',
     judge=['화면', '화면', '화면', '화면'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-652'], src=['CR-205'], pri='Medium'),

dict(id='IT-01-048', flow='채팅 분당 요청 한도 화면', kind='예외', title='채팅창이 429 를 받으면 남은 초와 함께 다시 시도 안내를 보이고, 만료 토큰의 401 은 그대로다',
     pre='채팅 한도 1 인 테넌트의 위젯을 띄운 소비앱 화면(ko). 같은 테넌트의 만료된 위젯 토큰 하나',
     steps='1. 질문을 보내 답을 받은 뒤 같은 분 안에 질문을 1번 더 보낸다\n'
           '2. 안내에 적힌 초가 지난 뒤 「다시 시도」 를 누른다\n'
           '3. 위젯 언어를 en 으로 바꿔 1 을 되풀이한다\n'
           '4. 만료된 위젯 토큰으로 위젯을 띄워 질문을 보낸다',
     expect='1. 답 자리에 「질문이 많아 잠시 받을 수 없습니다. N초 뒤 「다시 시도」를 눌러 주세요」 가 보이고 N 은 응답의 retry_after_seconds 와 같다. 보낸 질문은 지워지지 않는다\n'
            '2. 질문이 다시 가고 답이 온다\n'
            '3. 「Too many requests right now. Try again in N seconds」\n'
            '4. 429 문구가 아니라 기존 인증 만료 처리(토큰 갱신 또는 401 안내)가 그대로다',
     judge=['화면', '화면', '화면', '화면'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-652'], src=['CR-205'], pri='High'),

dict(id='IT-01-049', flow='채팅 분당 요청 한도', kind='정상', title='V96 반영 뒤 이미 있는 테넌트의 채팅 한도는 그때의 분당 요청 한도와 같고 운영 설정에 채팅 한도 4행이 있다',
     pre='V95 까지 올라간 master DB 사본(구독 행 둘 이상, 분당 요청 한도가 서로 다름)과 CR-205 가 든 서버',
     steps='1. 반영 전 subscriptions 의 tenant_id, api_rpm_limit 을 적어 둔다\n'
           '2. 서버를 띄워 Flyway 를 돌리고 flyway_schema_history 의 마지막 행을 본다\n'
           '3. subscriptions 의 tenant_id, api_rpm_limit, chat_rpm_limit 을 본다\n'
           '4. global_config 의 plan. 으로 시작하는 행을 본다\n'
           '5. 59 의 lexflow 테넌트 구독을 조회한다',
     expect='1. (기록)\n'
            '2. 마지막 행이 V96 이고 success 다\n'
            '3. 모든 행의 chat_rpm_limit 이 같은 행의 api_rpm_limit 과 같고 NULL 이 없다\n'
            '4. plan.free.chat-rpm-limit 60, plan.starter.chat-rpm-limit 300, plan.pro.chat-rpm-limit 1000, plan.enterprise.chat-rpm-limit 0 이 있고 기존 api-rpm-limit 4행은 그대로다\n'
            '5. apiRpmLimit 300, chatRpmLimit 300',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['BIZ-298'], src=['CR-205'], pri='High'),

]
