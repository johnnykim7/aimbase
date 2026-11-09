# 영역 05 같은 목소리 음성 생성 시험항목. 근거는 CR-166, CR-167, CR-171(T1-1 PRD-354 ~ 356, T1-3 BIZ-133 ~ 135, T3-2 같은 목소리 음성 생성 작업)이다.
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

dict(id='IT-05-007', flow='필요할 때만 싣기', kind='정상', title='일이 끝나고 정한 시간이 지나면 모델이 내려가 메모리가 돌아온다',
     pre='개발기 bp-wms 테넌트의 API 키, 생성기가 떠 있는 상태, 맡긴 적 없는 글 1개, 생성기 환경변수 TTS_IDLE_UNLOAD_SECONDS 값',
     steps='1. 글을 맡겨 COMPLETED 까지 본다\n'
           '2. 바로 생성기 건강 확인(api 컨테이너에서 GET http://tts-sidecar:8310/health)과 메모리(docker stats tts-sidecar)를 본다\n'
           '3. 정한 시간에 60초를 더 기다렸다 2 를 되풀이한다',
     expect='1. status 가 COMPLETED 다\n'
            '2. model 이 ready 고 메모리가 수 GB 다\n'
            '3. HTTP 200, model 이 unloaded 고 메모리가 500MB 아래다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-361', 'BIZ-139'], src=['CR-167']),

dict(id='IT-05-008', flow='필요할 때만 싣기', kind='정상', title='모델이 내려간 상태에서 맡긴 첫 문장이 실패하지 않고 기다림 상한 안에 끝난다',
     pre='개발기 bp-wms 테넌트의 API 키, 건강 확인 model 이 unloaded, 맡긴 적 없는 글 3개',
     steps='1. 첫 글을 맡긴다\n'
           '2. 10초 뒤(건강 확인 model 이 loading 일 때) 나머지 두 글을 맡긴다\n'
           '3. 세 작업을 10초마다 조회해 COMPLETED 나 FAILED 가 될 때까지 RUNNING 을 처음 본 때부터 잰다',
     expect='1. 202 를 받는다. 503 이 아니다\n'
            '2. 두 글도 202 를 받는다\n'
            '3. 세 작업 모두 COMPLETED 고, 첫 문장은 RUNNING 을 본 때부터 10분 안에 끝난다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-361', 'BIZ-139'], src=['CR-167']),

dict(id='IT-05-009', flow='필요할 때만 싣기', kind='정상', title='내렸다가 다시 실어도 같은 글은 같은 소리다',
     pre='개발기 생성기, api 컨테이너 안에서 부를 수 있는 생성기 창구',
     steps='1. 모델이 실린 상태에서 생성기 창구로 글 「검수대 3번으로 이동하세요.」 를 만들어 MP3 를 저장한다\n'
           '2. model 이 unloaded 가 될 때까지 기다린다\n'
           '3. 같은 글을 다시 만들어 저장하고 두 파일의 md5 를 견준다',
     expect='1. HTTP 200 과 MP3 가 온다\n'
            '2. 건강 확인 model 이 unloaded 다\n'
            '3. 두 md5 가 같다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-361', 'BIZ-134'], src=['CR-167']),

dict(id='IT-05-010', flow='필요할 때만 싣기', kind='예외', title='모델 프로세스가 죽으면 정한 시간 동안 다시 싣지 않고, 지나면 다시 싣는다',
     pre='개발기 생성기, 생성기 환경변수 TTS_LOAD_RETRY_SECONDS 값, 맡긴 적 없는 글 2개',
     steps='1. 글을 맡겨 만드는 동안 모델 프로세스만 끝낸다(docker compose exec 로 torch 를 불러온 자식 프로세스에 kill -9)\n'
           '2. 바로 건강 확인과 다른 글 맡기기를 부른다\n'
           '3. 정한 시간이 지난 뒤 다른 글을 다시 맡긴다',
     expect='1. 그 작업이 FAILED tts_generator_unavailable 이 되고 생성기 컨테이너는 다시 뜨지 않는다\n'
            '2. 건강 확인 503 backoff, 맡기기 503 tts_generator_unavailable\n'
            '3. 202 를 받고 COMPLETED 까지 간다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-140'], src=['CR-167']),

dict(id='IT-05-011', flow='운영 chaws 생성기', kind='정상', title='운영 chaws 에서 생성하는 동안 상한을 넘지 않고 운영 3PL 응답이 눈에 띄게 느려지지 않는다',
     pre='운영 chaws 에 생성기 반영 끝, 사용자의 운영 반영 승인, 운영 bp-wms 가 맡긴 문장이 도는 때',
     steps='1. 생성기가 돌기 전에 운영 3PL 건강 확인 응답 시간을 20번 잰다\n'
           '2. 생성기가 도는 동안 1 을 되풀이한다\n'
           '3. 생성기 컨테이너의 CPU 와 메모리를 본다(docker stats)',
     expect='1. 가운데 값과 가장 느린 값을 적는다\n'
            '2. 1 과 견줘 가운데 값의 차이가 0.2초 안이다\n'
            '3. CPU 와 메모리가 CR-167 3.5 의 상한 안이다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-362'], src=['CR-167']),

dict(id='IT-05-012', flow='운영 chaws 생성기', kind='정상', title='운영 bp-wms 가 맡긴 문장이 운영에 저장되고 일이 끝나면 메모리가 돌아온다',
     pre='운영 chaws 에 생성기 반영 끝, 운영 bp-wms 가 문장을 맡길 일(현장 화면 사용, 바구니 저장, wms 재배포 중 하나)',
     steps='1. 운영 DB 를 읽기 전용으로 조회해 wms.field_voice_clips 의 행 수를 본다\n'
           '2. 저장된 소리 하나를 내려받아 듣는다\n'
           '3. 일이 끝나고 정한 시간이 지난 뒤 생성기 메모리를 본다',
     expect='1. 1행 이상이다\n'
            '2. 분류대 299개와 같은 목소리로 들린다\n'
            '3. 500MB 아래다',
     judge=['서버', '사람', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-362'], src=['CR-167']),

dict(id='IT-05-013', flow='음성 생성 작업', kind='정상', title='한 글자 숫자를 맡겨도 빈 소리가 아니라 그 글자를 읽은 소리가 온다',
     pre='개발기 bp-wms 테넌트의 API 키, 생성기가 떠 있는 상태(GET /health 200)',
     steps='1. 일, 이, 육, 팔 을 하나씩 맡기고(POST /api/v1/speech/tts-jobs, 본문 voice "field-voice-07") 네 작업이 COMPLETED 나 FAILED 가 될 때까지 조회한다\n'
           '2. 받은 소리 네 개의 최대 음량을 잰다(ffmpeg volumedetect)\n'
           '3. 받은 소리 네 개를 듣는다',
     expect='1. 네 작업 모두 COMPLETED 이고 duration_sec 이 0.25 이상이다\n'
            '2. 네 소리 모두 최대 음량이 -40 dB 이상이다\n'
            '3. 네 소리가 각각 일, 이, 육, 팔 로 들리고 「육」 은 받침 ㄱ 까지 들린다',
     judge=['서버', '서버', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-355'], src=['CR-171'], pri='High'),

dict(id='IT-05-014', flow='음성 생성 작업', kind='예외', title='빈 소리로 끝난 작업은 같은 글을 다시 맡겨도 돌려주지 않고 새로 만든다',
     pre='소리 길이가 0.25초보다 짧게 끝난 COMPLETED 작업. 생성기가 이제 이런 작업을 만들지 않아 단위시험 TC-TTS-117 이 만든다',
     steps='1. 그 작업과 같은 음색, 같은 글로 다시 맡긴다',
     expect='1. reused false 와 앞 작업과 다른 job_id 가 오고, 새 작업이 생성기로 간다',
     judge=['단위'],
     pair={1: [1]},
     req=['PRD-354'], src=['CR-171']),

dict(id='IT-05-015', flow='음성 생성 작업', kind='정상', title='같은 글을 차례만 올려 맡기면 글은 그대로이고 다른 소리가 온다',
     pre='개발기 bp-wms 테넌트의 API 키, 생성기가 떠 있는 상태(GET /health 200), 「에스, 팔십이번」 이 차례 없이 맡겨져 COMPLETED 로 있는 상태',
     steps='1. 「에스, 팔십이번」 을 take 0, 1, 2 로 하나씩 맡기고(POST /api/v1/speech/tts-jobs, 본문 voice "field-voice-07") 세 작업이 COMPLETED 가 될 때까지 조회한다\n'
           '2. take 1 로 한 번 더 맡긴다\n'
           '3. 받은 소리 셋을 듣는다',
     expect='1. take 0 은 reused true 이고 앞서 맡긴 작업 번호와 소리(md5)가 같다. take 1, 2 는 reused false, 새 작업 번호이고 세 소리의 md5 가 서로 다르다. 응답의 take 가 보낸 값과 같다\n'
            '2. reused true 이고 1 의 take 1 작업 번호가 온다\n'
            '3. 세 소리 모두 「에스, 팔십이번」 으로 들린다',
     judge=['서버', '서버', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-354', 'PRD-355'], src=['CR-176'], pri='High'),

dict(id='IT-05-016', flow='음성 생성 작업', kind='예외', title='차례가 0 보다 작거나 상한보다 크면 맡기지 않는다',
     pre='개발기 bp-wms 테넌트의 API 키',
     steps='1. 「에스, 팔십이번」 을 take -1 로 맡긴다\n'
           '2. take 11 로 맡긴다',
     expect='1. 400 take_out_of_range:max=10 이고 작업이 생기지 않는다\n'
            '2. 400 take_out_of_range:max=10 이고 작업이 생기지 않는다',
     judge=['단위', '단위'],
     pair={1: [1], 2: [2]},
     req=['PRD-354'], src=['CR-176']),

]
