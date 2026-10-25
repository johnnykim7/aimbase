# 영역 01 CLI 작업과 앱 도구 시험항목. 근거는 CR-152(T3-12 § 4.3), CR-153(T1-3 BIZ-125, T3-13 § 4.4, T3-5 TC-MCP-RES-001~005), CR-154(T1-1 PRD-107, T3-5 TC-AK-011~016), CR-155(운용 가이드 § 2-9), CR-156(T1-3 BIZ-126, T3-5 TC-AGR-001~006)이다.
# CR-152, CR-153 의 대상 기능(PRD-306, PRD-281)은 T1-1 에 행이 없어 req·fc 를 비워 두고 src 에 CR 번호를 적는다. CR-154 도 같은 영역이라 같게 둔다.

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

]
