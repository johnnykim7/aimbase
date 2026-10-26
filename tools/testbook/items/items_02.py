# 영역 02 음성 변환 시험항목. 근거는 CR-158(STT 연동 가이드 § 3, § 5 가용성, § 7 job 이 PENDING 에서 안 변함)이다.
# 긴 녹음 변환(transcribe-jobs)은 T1-1 에 행이 없어 req·fc 를 비워 두고 src 에 CR 번호를 적는다.

ITEMS = [

dict(id='IT-02-001', flow='전사 사이드카 상주', kind='예외', title='맥 전사 사이드카가 끝나도 저절로 다시 떠서 긴 녹음 변환이 끝난다',
     pre='transcribe-sidecar/deploy/install-mac.sh 로 사이드카를 설치한 맥, 개발기(옛 서버)에서 그 맥을 쓰는 stt_local 연결이 있는 테넌트의 API 키, 말한 문장을 아는 몇 초짜리 한국어 녹음 파일 1개',
     steps='1. 맥에서 8291 포트를 쥔 사이드카 프로세스를 끝내고(kill) 60초 기다린 뒤 건강 확인(GET http://<맥>:8291/health)을 부른다\n'
           '2. 그 테넌트 키로 개발기에 녹음 파일 변환을 요청(POST /api/v1/transcribe-jobs)하고 끝날 때까지 상태를 조회한다',
     expect='1. 건강 확인이 200 과 "status": "ok" 를 돌려준다\n'
            '2. 변환 상태가 PENDING 에 머물거나 FAILED 로 끝나지 않고 COMPLETED 가 된다\n'
            '3. 변환 결과 글이 녹음에서 말한 문장과 같다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2, 3]},
     src=['CR-158'], pri='High'),

]
