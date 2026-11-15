# 영역 24 위젯 화면별 대화와 함께 보는 대화 시험항목. 근거는 CR-194 문서 1.3 규칙, 3절 서버, 4절 위젯 동작이다.
# 대상 기능은 T1-1 PRD-630 부터 PRD-637 이라 req 에 그 번호를 적고 src 에 CR 번호를 적는다.
# 화면 문구와 메뉴 화면에서 처음 열 때의 목록 동작은 화면 컨셉 승인 뒤 기대 결과에 더한다.
# 서버 판정 방식(probe)은 시험준비 단계에서 채운다.

LOCAL = ('로컬 Aimbase api(bootRun)와 로컬 위젯 견본 화면. 시험 테넌트에 위젯 API 키 하나와 연결 하나. '
         '위젯 토큰은 사람 A(user_ref tb-cr194-a, user_name tb_kim)와 사람 B(user_ref tb-cr194-b, user_name tb_lee)로 받는다')
SCREEN = LOCAL + ('. 견본 화면에 메뉴 화면 둘(화면 키 tb:menu:orders, tb:menu:returns)과 건 화면 하나'
                  '(화면 키와 함께 보기 키 tb:case:cs:1)가 있고, 위젯 요소 하나를 두고 화면을 옮길 때 속성만 바꾼다')

ITEMS = [

dict(id='IT-24-001', flow='토큰 발급 칸', kind='정상', title='토큰 발급에 묻는 사람 이름과 함께 보기 키를 실을 수 있고 모르는 칸은 무시한다',
     pre=LOCAL,
     steps='1. user_ref, user_name, share_key 를 실어 위젯 토큰을 받고 토큰 내용을 본다\n'
           '2. 요청에 모르는 칸 extra_field 를 하나 더해 받는다\n'
           '3. share_key 에 공백과 한글을 넣어 받는다\n'
           '4. user_ref 없이 share_key 만 실어 받는다\n'
           '5. user_name 을 101자로 받는다',
     expect='1. 토큰에 user_name 과 share_key 가 실려 있다\n'
            '2. 토큰이 발급된다\n'
            '3. 400 widget_token_invalid:share_key\n'
            '4. 400 widget_token_invalid:user_ref\n'
            '5. 400 widget_token_invalid:user_name',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-637', 'BIZ-277'], src=['CR-194'], pri='High'),

dict(id='IT-24-002', flow='대화 접근', kind='예외', title='남의 대화 번호로 물으면 응답을 시작하기 전에 거절되고 앞사람 대화가 모델에 들어가지 않는다',
     pre=LOCAL + '. 사람 A 가 함께 보기 키 없이 질문한 대화 하나가 있다',
     steps='1. 사람 B 토큰으로 A 의 대화 번호를 실어 대화를 요청한다\n'
           '2. 사람 B 토큰으로 A 의 대화 이어보기를 부른다\n'
           '3. A 의 대화 메시지 수를 본다',
     expect='1. 404 session_not_found 이고 응답 스트림이 시작되지 않는다\n'
            '2. 404 session_not_found\n'
            '3. 1번 요청 앞과 같다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-636', 'BIZ-275'], src=['CR-194'], pri='High'),

dict(id='IT-24-003', flow='함께 보는 대화 열기', kind='정상', title='같은 함께 보기 키를 받은 두 사람은 같은 대화 하나를 연다',
     pre=LOCAL,
     steps='1. 사람 A 를 함께 보기 키 tb:case:cs:1 토큰으로 함께 보는 대화를 연다\n'
           '2. 사람 B 를 같은 키 토큰으로 연다\n'
           '3. 다른 키 tb:case:cs:2 토큰으로 연다\n'
           '4. 함께 보기 키 없는 토큰으로 연다\n'
           '5. 새 키 tb:case:cs:3 으로 두 사람이 동시에 연다',
     expect='1. 대화 번호와 created true\n'
            '2. 1번과 같은 대화 번호와 created false\n'
            '3. 1번과 다른 대화 번호\n'
            '4. 400 share_key_required\n'
            '5. 두 응답의 대화 번호가 같고 그 키의 대화 행이 하나다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-632', 'BIZ-276'], src=['CR-194'], pri='High'),

dict(id='IT-24-004', flow='함께 보는 대화 접근', kind='예외', title='함께 보는 대화는 같은 키의 토큰만 읽고, 이어보기에 질문마다 묻는 사람이 실린다',
     pre=LOCAL + '. 함께 보기 키 tb:case:cs:1 대화에 사람 A 와 B 가 한 번씩 질문했다',
     steps='1. 같은 키 사람 A 토큰으로 이어보기를 부른다\n'
           '2. 다른 키 tb:case:cs:2 토큰으로 같은 대화 이어보기와 대화 요청을 부른다\n'
           '3. 사람 A 토큰으로 대화 목록을 부른다',
     expect='1. shared true, 첫 질문 asker 가 tb-cr194-a, tb_kim 이고 둘째 질문 asker 가 tb-cr194-b, tb_lee 다\n'
            '2. 둘 다 404 session_not_found\n'
            '3. 함께 보는 대화가 목록에 없다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-632', 'PRD-633', 'BIZ-275', 'BIZ-278'], src=['CR-194'], pri='High'),

dict(id='IT-24-005', flow='함께 보는 대화 되감기', kind='예외', title='함께 보는 대화의 다시 생성은 마지막 질문을 한 사람만 하고 삭제와 이름 바꾸기는 막힌다',
     pre=LOCAL + '. 함께 보기 키 tb:case:cs:1 대화에 사람 A 가 묻고 그 뒤 사람 B 가 물었다',
     steps='1. 사람 A 토큰으로 마지막 질문 묶음을 다시 생성한다\n'
           '2. 사람 B 토큰으로 마지막 질문 묶음을 다시 생성한다\n'
           '3. 사람 B 토큰으로 A 의 앞 질문 묶음부터 되감는다\n'
           '4. 사람 B 토큰으로 그 대화의 이름 바꾸기와 삭제를 부른다',
     expect='1. 409 rewind_rejected:shared\n'
            '2. 다시 생성된다\n'
            '3. 409 rewind_rejected:shared\n'
            '4. 둘 다 404 session_not_found',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['BIZ-280'], src=['CR-194'], pri='Medium'),

dict(id='IT-24-006', flow='메뉴 화면 대화 목록', kind='정상', title='메뉴 화면에서는 그 화면에서 나눈 내 대화만 목록에 보인다',
     pre=SCREEN + '. 사람 A 가 orders 에서 대화 둘, returns 에서 대화 하나를 나눴고 사람 B 도 orders 에서 대화 하나를 나눴다',
     steps='1. 사람 A 로 orders 화면에서 대화창을 열고 대화 목록을 본다\n'
           '2. returns 화면으로 옮겨 대화 목록을 본다\n'
           '3. returns 에서 새 대화로 질문한 뒤 대화 목록을 본다',
     expect='1. orders 의 A 대화 둘만 최신 순으로 보이고 B 의 대화와 returns 대화는 없다\n'
            '2. returns 대화 하나만 보인다\n'
            '3. returns 목록에 방금 대화가 더해지고 orders 목록은 그대로다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-630', 'PRD-631', 'BIZ-277'], src=['CR-194'], pri='High'),

dict(id='IT-24-007', flow='화면을 옮길 때', kind='정상', title='대화창을 연 채로 다른 화면에 다녀오면 앞 대화가 그대로 있다',
     pre=SCREEN + '. 사람 A 가 orders 에서 대화 둘을 나눴다',
     steps='1. orders 에서 대화창을 열고 목록에서 오래된 대화를 골라 연다\n'
           '2. 대화창을 연 채로 returns 화면으로 옮긴다\n'
           '3. 다시 orders 화면으로 돌아온다\n'
           '4. 응답이 나오는 중에 returns 화면으로 옮긴다',
     expect='1. 고른 대화의 질문과 응답이 보인다\n'
            '2. 대화창이 열린 채 returns 의 대화로 바뀐다\n'
            '3. 1번에서 고른 대화가 그대로 보인다\n'
            '4. 응답이 멈추고 returns 의 대화로 바뀐다. orders 로 돌아오면 멈춘 응답이 그 대화에 남아 있다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-635', 'BIZ-279'], src=['CR-194'], pri='High'),

dict(id='IT-24-008', flow='건 화면 함께 보기', kind='정상', title='건 화면에서는 그 건을 여는 사람 모두가 같은 대화를 보고 질문마다 묻는 사람이 보인다',
     pre=SCREEN + '. 건 화면은 history-list off 이고, 사람 A 와 B 는 다른 브라우저로 같은 건 화면을 연다',
     steps='1. 사람 A 로 건 화면 대화창을 열고 질문한다\n'
           '2. 사람 B 로 같은 건 화면 대화창을 연다\n'
           '3. 사람 B 가 이어 질문한다\n'
           '4. 사람 A 가 대화창을 닫았다가 다시 연다',
     expect='1. 대화 목록 단추와 새 대화 단추가 없다\n'
            '2. A 의 질문과 응답이 보이고 질문 위에 tb_kim 이 보인다\n'
            '3. B 의 질문 위에 tb_lee 와 내 질문 표시가 보인다\n'
            '4. A 화면에 B 의 질문과 응답이 tb_lee 로 보인다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-632', 'PRD-633', 'PRD-634', 'BIZ-278'], src=['CR-194'], pri='High'),

dict(id='IT-24-009', flow='대화 목록 끄기', kind='정상', title='history-list 를 off 로 바꾸면 대화 목록과 목록 단추가 바로 사라지고 되돌리면 다시 보인다',
     pre=SCREEN,
     steps='1. orders 화면에서 대화창을 열고 history-list 를 off 로 바꾼다\n'
           '2. history-list 속성을 뺀다',
     expect='1. 대화 목록과 목록 단추가 바로 사라지고 보던 대화는 그대로다\n'
            '2. 목록 단추가 다시 보인다',
     judge=['합동', '합동'],
     pair={1: [1], 2: [2]},
     req=['PRD-634'], src=['CR-194'], pri='Medium'),

dict(id='IT-24-010', flow='속성 없는 소비앱', kind='회귀', title='screen-key 와 history-list 를 주지 않은 위젯은 CR-178 그대로 돈다',
     pre=LOCAL + '. 견본 화면에서 위젯에 화면 키와 대화 목록 속성을 주지 않는다. 사람 A 가 화면 키 있는 대화와 없는 대화를 하나씩 나눴다',
     steps='1. 대화창을 열고 대화 목록을 본다\n'
           '2. session-id 에 화면 키 없는 대화 번호를 주고 다시 붙인다\n'
           '3. user_ref 없는 토큰으로 붙인다',
     expect='1. 두 대화가 모두 보인다\n'
            '2. 그 대화의 지난 질문과 응답이 보인다\n'
            '3. 대화 목록 단추가 없고 질문과 응답은 된다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-630', 'PRD-634'], src=['CR-194'], pri='High'),

]
