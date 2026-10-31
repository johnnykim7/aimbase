# 영역 06 음성 합성 맥 엔진과 목소리 선택 시험항목. 근거는 CR-165 9절(T1-1 PRD-357 ~ 360, T1-3 BIZ-136 ~ 138, T3-2 음성 합성)이다.
# 부르는 쪽은 bp-office 하나라 개발기에서 bp_office 테넌트 키로 본다. 같은 목소리로 들리는지는 사람이 듣고 정한다.
# OpenAI 로 가는 항목은 그 조직에 API 크레딧이 있어야 200 이 온다. 크레딧이 없으면 OpenAI 가 돌려준 429 가 그대로 온다.

ITEMS = [

dict(id='IT-06-001', flow='음성 합성 목록 목소리', kind='정상', title='목록 목소리로 브리핑 글을 보내면 맥 엔진이 만든 소리가 오고 날짜와 시각을 읽는 말로 읽는다',
     pre='개발기 bp_office 테넌트의 API 키, 맥 음성 엔진이 떠 있는 상태(GET /health 200), bp_office 의 tts_local 연결, '
         '날짜와 시각이 든 브리핑 글(예: 「주간 회의는 2026-10-31 14:00, 결재 마감은 2026.11.02 09:30 입니다.」)',
     steps='1. 목소리 목록을 조회한다(GET /api/v1/speech/voices)\n'
           '2. default 가 true 인 목소리의 id 를 voice 로, 브리핑 글을 text 로 음성 합성을 부른다(POST /api/v1/speech/tts)\n'
           '3. 받은 소리를 MP3 로 저장해 듣는다',
     expect='1. 200 이고 voices 에 목소리가 하나 이상, default true 는 하나, 칸은 id, name, description, default 뿐이고 openai_voice 가 없다. speed 는 min 0.5, max 2.0, default 1.0\n'
            '2. 200, Content-Type audio/mpeg, 머리글 X-Aimbase-TTS-Engine 이 local\n'
            '3. 「2026년 10월 31일 오후 2시」, 「2026년 11월 2일 오전 9시 30분」 으로 읽고 사용자가 고른 후보3 목소리로 들린다',
     judge=['서버', '서버', '사람'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-357', 'PRD-358', 'PRD-359', 'PRD-360'], src=['CR-165'], pri='High'),

dict(id='IT-06-002', flow='음성 합성 목록 목소리', kind='정상', title='글이 달라도 같은 목소리로 읽는다',
     pre='IT-06-001 과 같은 상태, IT-06-001 과 다른 글 2개',
     steps='1. 같은 목소리로 다른 글 2개를 각각 음성 합성한다\n'
           '2. IT-06-001 의 소리와 함께 듣는다',
     expect='1. 둘 다 200 이고 머리글 X-Aimbase-TTS-Engine 이 local\n'
            '2. 세 소리가 같은 사람 목소리로 들린다',
     judge=['서버', '사람'],
     pair={1: [1], 2: [2]},
     req=['PRD-360'], src=['CR-165']),

dict(id='IT-06-003', flow='음성 합성 목록 목소리', kind='예외', title='맥 엔진이 닿지 않으면 OpenAI 로 넘어간다',
     pre='IT-06-001 과 같은 상태, bp_office 의 OpenAI 연결, 맥 음성 엔진을 내린 상태(launchctl bootout)',
     steps='1. 목록 목소리로 IT-06-001 의 브리핑 글을 음성 합성한다\n'
           '2. 맥 음성 엔진을 다시 올린다(install-mac.sh)',
     expect='1. 크레딧이 있으면 200, audio/mpeg, 머리글 X-Aimbase-TTS-Engine 이 openai. 크레딧이 없으면 OpenAI 가 돌려준 429 와 본문이 그대로 온다\n'
            '2. GET /health 200 이고 다시 부르면 머리글이 local',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-357'], src=['CR-165']),

dict(id='IT-06-004', flow='음성 합성 목록 목소리', kind='예외', title='빈 글과 범위 밖 speed 는 400 으로 거절된다',
     pre='개발기 bp_office 테넌트의 API 키, 목록 목소리 id',
     steps='1. text 를 공백만 넣어 부른다\n'
           '2. 목록 목소리에 speed 0.4 로 부른다\n'
           '3. 목록 목소리에 speed 2.1 로 부른다',
     expect='1. 400\n'
            '2. 400\n'
            '3. 400',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-357'], src=['CR-165']),

dict(id='IT-06-005', flow='음성 합성 목록 목소리', kind='정상', title='맥이 꺼져 있어도 목소리 목록은 나온다',
     pre='개발기 bp_office 테넌트의 API 키, 맥 음성 엔진을 내린 상태',
     steps='1. 목소리 목록을 조회한다(GET /api/v1/speech/voices)',
     expect='1. 200 이고 IT-06-001 과 같은 목록이 온다',
     judge=['서버'],
     pair={1: [1]},
     req=['PRD-359'], src=['CR-165']),

dict(id='IT-06-006', flow='음성 합성 목록 밖 목소리', kind='정상', title='목록에 없는 목소리는 지금처럼 OpenAI 로 가고 글을 바꾸지 않는다',
     pre='개발기 bp_office 테넌트의 API 키, bp_office 의 OpenAI 연결과 API 크레딧',
     steps='1. voice "nova", 날짜가 든 글로 음성 합성을 부른다\n'
           '2. 받은 소리를 듣는다',
     expect='1. 200, audio/mpeg, 머리글 X-Aimbase-TTS-Engine 이 openai\n'
            '2. OpenAI nova 목소리로 들린다',
     judge=['서버', '사람'],
     pair={1: [1], 2: [2]},
     req=['PRD-357'], src=['CR-165']),

]
