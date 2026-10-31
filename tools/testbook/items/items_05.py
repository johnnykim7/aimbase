# 영역 05 같은 목소리 음성 생성 시험항목. 근거는 CR-166(T1-1 PRD-354 ~ 356, T1-3 BIZ-133 ~ 135, T3-2 같은 목소리 음성 생성 작업)이다.
# 부르는 쪽은 bp-wms 하나라 개발기(59)에서 bp-wms 테넌트 키로 본다. 같은 목소리로 들리는지는 사람이 듣고 정한다.

ITEMS = [

dict(id='IT-05-001', flow='음성 생성 작업', kind='정상', title='음색과 글을 맡기면 작업 번호를 바로 받고 조회하면 끝난 소리가 온다',
     pre='개발기 bp-wms 테넌트의 API 키, 생성기가 떠 있는 상태(GET /health 200), 그 테넌트에 맡긴 적 없는 글 1개',
     steps='1. 작업을 맡긴다(POST /api/v1/speech/tts-jobs, 본문 voice "field-voice-07", text 그 글)\n'
           '2. 10초마다 작업을 조회(GET /api/v1/speech/tts-jobs/{job_id})해 COMPLETED 나 FAILED 가 될 때까지 본다\n'
           '3. 받은 audio_base64 를 MP3 로 저장해 듣는다',
     expect='1. 2초 안에 202 와 job_id, status PENDING, reused false 가 온다\n'
            '2. status 가 RUNNING 을 거쳐 COMPLETED 가 되고 mime_type audio/mpeg, audio_base64, duration_sec, elapsed_sec 이 있다\n'
            '3. 맡긴 글을 그대로 읽고 분류대 299개와 같은 목소리로 들린다',
     judge=['서버', '서버', '사람'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-354', 'PRD-355'], src=['CR-166'], pri='High'),

dict(id='IT-05-002', flow='음성 생성 작업', kind='정상', title='같은 글을 다시 맡기면 새로 만들지 않고 같은 작업을 돌려준다',
     pre='IT-05-001 로 COMPLETED 가 된 작업',
     steps='1. IT-05-001 과 같은 음색, 같은 글로 다시 맡긴다',
     expect='1. 202 와 IT-05-001 과 같은 job_id, reused true, status COMPLETED 가 온다',
     judge=['서버'],
     pair={1: [1]},
     req=['PRD-354'], src=['CR-166']),

dict(id='IT-05-003', flow='음성 생성 작업', kind='예외', title='빈 글, 200자 넘는 글, 없는 음색, 없는 작업은 정해진 오류 코드로 거절된다',
     pre='개발기 bp-wms 테넌트의 API 키',
     steps='1. text 를 공백만 넣어 맡긴다\n'
           '2. 201자 글로 맡긴다\n'
           '3. voice 를 "no-such-voice" 로 맡긴다\n'
           '4. 없는 job_id(새 UUID)로 조회한다',
     expect='1. 400 과 error text_required\n'
            '2. 400 과 error text_too_long:max=200\n'
            '3. 404 와 error voice_not_found:no-such-voice\n'
            '4. 404 와 error job_not_found',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-354'], src=['CR-166']),

dict(id='IT-05-004', flow='음성 생성 작업', kind='예외', title='다른 테넌트가 맡긴 작업은 조회되지 않는다',
     pre='개발기 bp-wms 테넌트의 API 키, 다른 테넌트의 API 키, IT-05-001 의 job_id',
     steps='1. 다른 테넌트 키로 IT-05-001 의 job_id 를 조회한다',
     expect='1. 404 와 error job_not_found',
     judge=['서버'],
     pair={1: [1]},
     req=['PRD-354'], src=['CR-166']),

dict(id='IT-05-005', flow='음성 생성 작업', kind='정상', title='몰아서 맡긴 작업은 한 번에 하나씩 맡긴 순서대로 끝난다',
     pre='개발기 bp-wms 테넌트의 API 키, 맡긴 적 없는 글 3개',
     steps='1. 세 글을 1초 간격으로 맡긴다\n'
           '2. 셋이 끝날 때까지 10초마다 조회한다',
     expect='1. 셋 다 202 와 PENDING\n'
            '2. 셋의 started_at 이 맡긴 순서이고, 앞 작업의 finished_at 이 다음 작업의 started_at 보다 이르다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-354'], src=['CR-166']),

dict(id='IT-05-006', flow='생성기 자원', kind='정상', title='생성하는 동안에도 Aimbase 의 다른 응답이 눈에 띄게 느려지지 않는다',
     pre='개발기 bp-wms 테넌트의 API 키, 맡긴 적 없는 글 5개',
     steps='1. 생성 작업이 없을 때 api 건강 확인(GET /actuator/health)과 작업 조회를 각 20번 부르고 응답 시간을 잰다\n'
           '2. 다섯 글을 맡겨 생성기가 도는 동안 1 을 되풀이한다\n'
           '3. 생성기 컨테이너의 CPU 사용을 본다(docker stats tts-sidecar)',
     expect='1. 응답 시간의 가운데 값과 가장 느린 값을 적는다\n'
            '2. 1 과 견줘 가운데 값의 차이가 0.2초 안이다\n'
            '3. 생성기 CPU 가 800% 를 넘지 않는다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-356'], src=['CR-166']),

]
