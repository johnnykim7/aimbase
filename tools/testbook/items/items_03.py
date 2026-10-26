# 영역 03 워크플로우 첨부 읽기 시험항목. 근거는 CR-159(API 가이드 § 4-4 실행 API session_id, 운용 가이드 LLM_CALL attachments)다.
# T1-1 에 행이 없어 req·fc 를 비워 두고 src 에 CR 번호를 적는다.

ITEMS = [

dict(id='IT-03-001', flow='첨부 그림 읽기', kind='정상', title='워크플로우 LLM 단계가 첨부로 받은 휴대폰 사진과 그림만 있는 스캔 PDF 의 글을 읽는다',
     pre='개발기(옛 서버) bp_office 테넌트의 API 키, LLM_CALL 단계 config 에 attachments "{{input.files}}" 를 둔 추출 워크플로우(CLI 연결 cli-runner-bp-office-001), '
         '적힌 글을 아는 휴대폰 사진 1장(긴 변 1568px 초과, 세로로 찍어 EXIF 방향 값이 있는 것)과 그림만 있는 3MB 이하 스캔 PDF 1개',
     steps='1. 세션 번호 하나를 정해 두 파일을 첨부로 올린다(POST /api/v1/chat/attachments, multipart session_id, file)\n'
           '2. 같은 세션 번호로 워크플로우를 실행(POST /api/v1/workflows/{id}/run?session_id=..., 입력 files 에 받은 첨부 번호 목록)하고 끝날 때까지 실행을 조회(GET /api/v1/workflows/runs/{runId})한다\n'
           '3. 그 실행의 LLM 단계 요청 기록을 조회(GET /api/v1/workflows/runs/{runId}/events)한다',
     expect='1. 두 올리기가 첨부 번호를 돌려준다\n'
            '2. 실행이 COMPLETED 로 끝나고 결과에 두 파일에 적힌 글이 담긴다\n'
            '3. 요청 기록 프롬프트 끝에 "[첨부] <사진 파일명> image/jpeg <크기>B, 그림(줄임 image/jpeg)" 줄과 "[첨부] <PDF 파일명> application/pdf <크기>B, PDF 통째" 줄이 있다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     src=['CR-159'], pri='High'),

dict(id='IT-03-002', flow='첨부 그림 읽기', kind='정상', title='3MB 를 초과하는 스캔 PDF 는 쪽 그림으로 보내 글을 읽는다',
     pre='IT-03-001 과 같은 테넌트와 워크플로우, rag 사이드카가 떠 있는 개발기, 적힌 글을 아는 그림만 있는 스캔 PDF 1개(3MB 초과, 20쪽 이하)',
     steps='1. 세션 번호 하나로 PDF 를 첨부로 올리고 같은 세션 번호로 워크플로우를 실행해 끝날 때까지 조회한다(API 는 IT-03-001 과 같다)\n'
           '2. 그 실행의 LLM 단계 요청 기록을 조회(GET /api/v1/workflows/runs/{runId}/events)한다',
     expect='1. 실행이 COMPLETED 로 끝나고 결과에 PDF 에 적힌 글이 담긴다\n'
            '2. 요청 기록 프롬프트 끝에 "[첨부] <PDF 파일명> application/pdf <크기>B, 쪽 그림 N장" 줄이 있고 N 이 PDF 쪽 수와 같다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     src=['CR-159'], pri='High'),

]
