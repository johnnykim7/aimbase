# 영역 10 채팅 위젯 틀과 영어 화면 시험항목. 근거는 CR-177 문서 8.2 통합 시험항목, 3.3 화면에서 바뀌는 것, 4절 영어 화면이다.
# 대상 기능은 T1-1 PRD-400 ~ 402 라 req 에 그 번호를 적고 src 에 CR 번호를 적는다.
# 백오피스2 는 locale 을 주지 않으므로 locale 과 언어별 값 확인은 로컬 위젯 견본 화면에서 본다.
# 틀로 옮긴 뒤 CR-175 기능이 그대로인지는 영역 09 항목을 다시 돌려 본다(IT-10-008).

WES_CS = ('개발기 백오피스2(59.8.160.12:3201) CS 문의 상세 9471 의 「AI 에게 질문」 위젯. 연결 cli-runner-bp-wes-cs-001')
LOCAL = '로컬 위젯 견본 화면'

# 서버 판정은 개발기 WES 테넌트에 위젯 토큰으로 위젯 설정 조회를 부른다. 로그인은 tools/testbook/dev_wes_token.sh 다.
CONN = 'cli-runner-bp-wes-cs-001'
WES = 'wes:tb-cr177'
SETTINGS = '/api/v1/chat/widget-settings?connection_id=' + CONN


def settings(locale=None):
    """위젯 설정 조회. locale 을 None 으로 주면 인자를 싣지 않는다."""
    return ('api', WES, 'GET', SETTINGS + ('&locale=' + locale if locale else ''))


def label_of(i, code):
    return "[x['label'] for x in r[%d]['body']['data']['feedback_reasons'] if x['code'] == '%s']" % (i, code)


ITEMS = [

dict(id='IT-10-001', flow='화면 언어', kind='정상', title='더 보기 「화면 언어」에서 English 를 고르면 위젯 화면 글이 바로 영어로 바뀌고, 다시 열어도 남는다',
     pre=WES_CS + '. 질문 하나를 주고받은 대화',
     steps='1. 머리줄 「더 보기」를 누르고 「화면 언어」를 누른다\n'
           '2. 「English」를 누른다\n'
           '3. 머리줄 단추, 입력칸, + 메뉴, 더 보기 메뉴에 마우스를 올리거나 연다\n'
           '4. 앞서 주고받은 질문과 답을 본다\n'
           '5. 브라우저를 새로고침하고 위젯을 다시 연다',
     expect='1. 「한국어」, 「English」 두 줄이 보이고 「한국어」에 표시가 있다\n'
            '2. 메뉴가 닫히고 입력칸 안내 글이 영어로 바뀐다\n'
            '3. 도움말과 메뉴 글이 "New chat", "Expand", "More", "Add files", "Upload files", "Text size", "Language" 처럼 영어다\n'
            '4. 질문과 답 글은 바뀌지 않고, 답 아래 단추 도움말("Copy", "Regenerate")은 영어다\n'
            '5. 처음부터 영어로 보인다',
     judge=['합동', '합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-401'], src=['CR-177'], pri='High'),

dict(id='IT-10-002', flow='화면 언어', kind='정상', title='붙이는 앱이 locale 을 주면 그 언어로 뜨고, 사람이 고른 언어가 그보다 앞선다',
     pre=LOCAL + '. locale="en" 을 준 화면, locale="en-US" 를 준 화면, locale="fr" 를 준 화면. 브라우저에 기억한 화면 언어가 없다',
     steps='1. locale="en" 화면을 연다\n'
           '2. locale="en-US" 화면을 연다\n'
           '3. locale="fr" 화면을 연다\n'
           '4. locale="en" 화면에서 더 보기 「Language」에서 「한국어」를 고르고 새로고침한다',
     expect='1. 처음부터 영어로 보인다\n'
            '2. 처음부터 영어로 보인다\n'
            '3. 한국어로 보인다\n'
            '4. 한국어로 보인다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-401'], src=['CR-177'], pri='Medium'),

dict(id='IT-10-003', flow='화면 언어', kind='정상', title='영어 화면에서 아쉬워요를 누르면 이유가 영어 이름으로 보이고, 위젯 설정 조회가 locale 대로 이름을 준다',
     pre=WES_CS + '. 화면 언어 English. 답 하나가 있는 대화',
     steps='1. 답 아래 아쉬워요 단추를 누른다\n'
           '2. 위젯 설정 조회를 locale=en 으로 부른다\n'
           '3. 위젯 설정 조회를 locale 없이 부른다\n'
           '4. 화면 언어를 한국어로 바꾸고 다른 답의 아쉬워요를 누른다',
     expect='1. 이유 칩이 "Incorrect information", "Too long" 처럼 영어다\n'
            '2. feedback_reasons 의 WRONG_INFO 이름이 "Incorrect information" 이다\n'
            '3. feedback_reasons 의 WRONG_INFO 이름이 "틀린 정보" 다\n'
            '4. 이유 칩이 "틀린 정보" 처럼 한국어다',
     judge=['합동', '서버', '서버', '합동'],
     probe={2: dict(steps=[settings('en')], check=label_of(0, 'WRONG_INFO') + " == ['Incorrect information']"),
            3: dict(steps=[settings()], check=label_of(0, 'WRONG_INFO') + " == ['틀린 정보']")},
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-402'], src=['CR-177'], pri='Medium'),

dict(id='IT-10-004', flow='화면 언어', kind='정상', title='붙이는 앱이 첫 화면 안내와 추천 질문을 언어별로 주면 화면 언어에 맞는 값이 보인다',
     pre=LOCAL + '. empty-title 을 {"ko": "무엇이든 물어보세요", "en": "Ask me anything"}, suggestions 를 {"ko": ["주문 조회"], "en": ["Find my order"]} 로 준 화면과, empty-title 을 글 하나 "안내" 로 준 화면',
     steps='1. 첫 화면을 본다\n'
           '2. 화면 언어를 English 로 바꾼다\n'
           '3. 글 하나를 준 화면을 English 로 본다',
     expect='1. "무엇이든 물어보세요" 와 추천 질문 "주문 조회" 가 보인다\n'
            '2. "Ask me anything" 과 추천 질문 "Find my order" 로 바로 바뀐다\n'
            '3. "안내" 가 그대로 보인다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-401'], src=['CR-177'], pri='Medium'),

dict(id='IT-10-005', flow='+ 메뉴', kind='정상', title='입력칸 + 단추의 「파일 올리기」로 첨부하면 지금 첨부와 같이 칩이 붙고, 끌어 놓기와 붙여 넣기도 그대로다',
     pre=WES_CS,
     steps='1. 입력칸 왼쪽 + 단추를 누른다\n'
           '2. 「파일 올리기」를 누르고 PNG 하나를 고른다\n'
           '3. PDF 하나를 입력칸에 끌어 놓는다\n'
           '4. 그림을 복사해 입력칸에 붙여 넣는다\n'
           '5. + 메뉴를 열고 메뉴 밖을 누른다',
     expect='1. 「파일 올리기」(보조 글 "이미지·PDF") 한 줄인 메뉴가 열린다\n'
            '2. 메뉴가 닫히고 그 파일의 첨부 칩이 붙는다\n'
            '3. PDF 첨부 칩이 붙는다\n'
            '4. 그림 첨부 칩이 붙는다\n'
            '5. 메뉴가 닫힌다',
     judge=['합동', '합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-400'], src=['CR-177'], pri='High'),

dict(id='IT-10-006', flow='더 보기 메뉴', kind='정상', title='더 보기 메뉴의 글자 크기가 틀로 옮긴 뒤에도 지금처럼 바뀌고 기억된다',
     pre=WES_CS,
     steps='1. 머리줄 「더 보기」를 누른다\n'
           '2. 글자 크기 「크게」를 누른다\n'
           '3. 새로고침하고 위젯을 다시 연다',
     expect='1. 구분선 아래에 글자 크기 줄과 「화면 언어」 줄이 보인다\n'
            '2. 본문과 입력칸 글자가 커진다\n'
            '3. 크게로 남아 있다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-400'], src=['CR-177'], pri='Medium'),

dict(id='IT-10-007', flow='화면 언어', kind='정상', title='영어 화면에서 답 안 표와 코드의 단추, 오류 글도 영어다',
     pre=WES_CS + '. 화면 언어 English',
     steps='1. "주문 세 건을 표로, 그리고 SQL 예시 한 줄을 코드로 보여 주세요" 를 보낸다\n'
           '2. 답 안 표와 코드 블록의 단추에 마우스를 올린다\n'
           '3. 이미지도 PDF 도 아닌 파일(zip)을 「Upload files」로 고른다',
     expect='1. 표와 코드 블록이 있는 답이 온다\n'
            '2. 표 단추 도움말("Download CSV", "Download Excel (.xlsx)", "Copy")과 코드 복사 도움말이 영어다\n'
            '3. 첨부할 수 없다는 알림이 영어로 보인다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-401'], src=['CR-177'], pri='Low'),

dict(id='IT-10-008', flow='회귀', kind='정상', title='틀로 옮긴 뒤에도 CR-175 대화 기본기(영역 09)가 모두 지금처럼 동작한다',
     pre=WES_CS,
     steps='1. 영역 09 시험항목 IT-09-001 ~ IT-09-025 를 다시 돌린다\n'
           '2. Esc 를 녹음 중, 답이 도는 중, 메뉴가 열린 중, 크게 보기 중에 차례로 누른다',
     expect='1. 영역 09 항목 판정이 CR-177 반영 전과 같다\n'
            '2. 녹음 취소, 응답 중지, 메뉴 닫기, 크게 보기 닫기가 그 순서로 하나씩 된다',
     judge=['합동', '합동'],
     pair={1: [1], 2: [2]},
     req=['PRD-400'], src=['CR-177'], pri='High'),

]
