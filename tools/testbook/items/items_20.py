# 영역 20 실행기가 만든 파일의 대화 파일 칩 시험항목. 근거는 CR-187 문서 1절 범위, 6절 파일 위치 안내, 7절 59 확인이다.
# 대상 기능 ID 가 없는 보정 CR 이라 req 에는 세션 작업 폴더에 만든 파일의 칩(PRD-460)을 적는다.
# 그 파일을 옆 창에서 고치는 것(원본 없는 파일, 실행기 연결)은 IT-14-002 가 덮는다.
# 파일 만들기 도구를 끈 채 보는 까닭: 도구가 있으면 실행기가 도구로 out/ 에 쓰므로 안내 틀을 거치지 않는다.

WES_CS = ('개발기 백오피스2(59.8.160.12:3201) CS 문의 상세 9471 의 「AI 에게 질문」 위젯. 연결 cli-runner-bp-wes-cs-001')
TOOL_OFF = '런타임 설정 chat.file-create.enabled 끔'
GUIDE_ON = '테넌트 bp_wes 프롬프트 템플릿 widget.artifact-out 활성'
DOCX_Q = "주간 회의 회의록을 docx 파일 하나로 만들어 주세요. 제목은 '주간 회의', 본문은 안건 세 줄이면 됩니다"
XLSX_Q = '같은 안건 세 줄을 엑셀 파일로도 만들어 주세요'

ITEMS = [

dict(id='IT-20-001', flow='파일 칩', kind='정상', title='CS 실행기가 저장 폴더를 듣지 않고 스스로 만든 파일도 답에 파일 칩이 붙고, 같은 대화의 둘째 파일도 붙는다',
     pre=WES_CS + '. ' + TOOL_OFF + '. ' + GUIDE_ON,
     steps='1. 새 대화에서 "' + DOCX_Q + '" 를 보내고 답이 끝날 때까지 기다린다\n'
           '2. 1 의 답 글을 읽는다\n'
           '3. 같은 대화에서 "' + XLSX_Q + '" 를 보낸다\n'
           '4. 59 서버에서 workspace-wes-cs/chat/{그 대화 세션}/out/ 과 workspace/chat/{그 대화 세션} 을 본다',
     expect='1. 답 아래 .docx 파일 칩이 보이고 칩을 누르면 옆 창에 제목 「주간 회의」 와 안건 세 줄이 보인다\n'
            '2. 답 글에 파일 이름만 있고 /data/ 나 /tmp/ 로 시작하는 전체 경로는 없다\n'
            '3. 둘째 답 아래 .xlsx 파일 칩이 보이고 칩을 누르면 옆 창 표에 안건 세 줄이 보인다\n'
            '4. workspace-wes-cs 쪽 out/ 에 docx 와 xlsx 가 있고, 다른 실행기와 함께 쓰는 workspace/chat 에는 그 세션 폴더가 없다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-460'], src=['CR-187'], pri='High'),

dict(id='IT-20-002', flow='안내 틀 끔', kind='예외', title='파일 위치 안내 틀을 비활성으로 두어도 CS 실행기 대화는 답이 오고 api 기록에 틀이 없다는 경고가 남는다',
     pre=WES_CS + '. 관리 화면 「프롬프트 템플릿」 에서 widget.artifact-out 을 비활성으로 두고 템플릿 캐시를 비운다(DELETE /api/v1/prompt-templates/cache)',
     steps='1. 새 대화에서 "안녕하세요, 오늘 할 일을 한 줄로 알려 주세요" 를 보낸다\n'
           '2. 59 api 기록에서 1 을 보낸 뒤의 widget.artifact-out 줄을 찾는다\n'
           '3. widget.artifact-out 을 다시 활성으로 두고 템플릿 캐시를 비운다',
     expect='1. 오류 안내 없이 답 글이 끝까지 온다\n'
            '2. 1 을 보낸 시각 뒤에 "Prompt template widget.artifact-out is missing" 경고 줄이 있다\n'
            '3. 「프롬프트 템플릿」 목록에서 widget.artifact-out 이 활성으로 보인다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-460'], src=['CR-187'], pri='Medium'),

]
