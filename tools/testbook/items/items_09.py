# 영역 09 채팅 위젯 대화 기본기 시험항목. 근거는 CR-175 문서 9.2 통합 시험항목 17개, 4.2 위젯 동작, 3절 서버 오류 표,
# 구현 메모의 결함 둘(CLI 연결에서 모델을 바꾼 뒤 새로 뜬 작업자가 앞 질문에 답함, 칸 안 위젯이 칸 높이를 넘어 늘어남)이다.
# 대상 기능은 T1-1 PRD-368 ~ 375, FE-042, FE-043 이라 req 에 그 번호를 적고 src 에 CR 번호를 적는다.
# 백오피스2 가 아직 넘기지 않는 옵션(추천 질문, 글자 크기 기본값)과 연결을 바꿔야 하는 확인은 로컬 위젯 견본 화면에서 본다.

WES_CS = ('개발기 백오피스2(59.8.160.12:3201) CS 문의 상세 9471 의 「AI 에게 질문」 위젯. 연결 cli-runner-bp-wes-cs-001 에 '
          '허용 모델 Opus 4.5, Sonnet 5.5, Sonnet 4.5, Haiku 5.5, 기본 모델 claude-sonnet-4-5')

# 서버 판정은 화면 대신 개발기 WES 테넌트에 위젯 토큰(WES 서버가 받는 것과 같은 chat:stream, user_ref 없음)으로
# 요청을 보내 재료를 스스로 만든다. 로그인은 tools/testbook/dev_wes_token.sh, DB 는 dev_wes_db.sh 다.
# 생각은 끈다. 답이 빨리 끝나고 SSE 본문이 판정기가 읽는 길이(500자) 안에 든다.
# 위젯처럼 첫 질문부터 세션 번호를 정해 보낸다. 번호 없는 단발 요청은 같은 질문의 예전 답을 캐시에서 꺼내고 대화를 남기지 않는다(PRD-128).
CONN = 'cli-runner-bp-wes-cs-001'
WES = 'wes:tb-cr175'
CHAT = '/api/v1/chat/completions'
Q1 = '시험 질문입니다. 숫자 1 만 답해 주세요'
CTX = '화면 맥락: 서버 판정 시험'
S0 = '{val:0:data.session_id}'


def ask(text, model='claude-sonnet-4-5', session='tb-cr175-{stamp}', rewind=None, context=None, stream=False, thinking='off'):
    """위젯이 보내는 대화 요청. 화면 맥락은 질문 앞 system 메시지로 싣는다. session 을 None 으로 주면 세션 번호를 싣지 않는다."""
    msgs = ([dict(role='system', content=context)] if context else []) + [dict(role='user', content=text)]
    body = dict(connection_id=CONN, model=model, stream=stream, thinking=thinking, messages=msgs)
    if session is not None:
        body['session_id'] = session
    if rewind is not None:
        body['rewind_from_seq'] = rewind
    return ('api', WES, 'POST', CHAT, body)


def rows(n):
    """n 번째 단계가 연 세션의 대화 행. 글 안 줄바꿈이 결과 행을 가르지 않게 글은 앞 여섯 글자만 본다."""
    return ('sql', "SELECT seq, role, turn_id, model, left(translate(content, E'\\n\\t', '  '), 6) AS head, "
                   "deleted_at IS NOT NULL AS del, delete_reason FROM conversation_messages "
                   "WHERE session_id = '{val:%d:data.session_id}' ORDER BY seq, created_at" % n)


def rejected(i, status, code):
    return f"r[{i}]['status'] == {status} and r[{i}]['body']['error'] == '{code}'"


def turn(i):
    return f"r[{i}]['body']['data']['turn_id']"


def answers_of(rows_i, step_i):
    return f"[x for x in r[{rows_i}] if x['turn_id'] == {turn(step_i)} and x['role'] == 'assistant']"


STOP_Q = '이 문의의 처리 절차를 단계마다 자세히 써 주세요'

ITEMS = [

dict(id='IT-09-001', flow='응답 중지', kind='정상', title='답이 나오는 중 응답 중지 단추나 Esc 로 멈추면 받은 데까지 남고, 그 답을 다시 생성할 수 있으며 다음 질문이 이어진다',
     pre=WES_CS,
     steps='1. 긴 답이 나올 질문("이 문의의 처리 절차를 단계마다 자세히 써 주세요")을 보내고 글이 나오는 중 「응답 중지」를 누른다\n'
           '2. 같은 질문을 다시 보내고 글이 나오는 중 Esc 를 누른다\n'
           '3. 테넌트 DB conversation_messages 에서 1 의 답 행을 본다\n'
           '4. 2 의 답 아래 「다시 생성」을 누른다\n'
           '5. "이 문의의 고객사 이름만 알려 주세요" 를 보낸다',
     expect='1. 누를 때까지 받은 글이 남고 그 아래 "응답을 중지했습니다" 가 붙는다\n'
            '2. Esc 로도 받은 글이 남고 그 아래 "응답을 중지했습니다" 가 붙는다\n'
            '3. 답 행의 content 가 "[중단됨] " 으로 시작한다\n'
            '4. 같은 질문의 새 답이 끝까지 오고 답 아래 버전 번호 "2/2" 가 보인다\n'
            '5. 고객사 이름을 말하는 답이 끝까지 온다',
     judge=['합동', '합동', '서버', '합동', '합동'],
     probe={3: dict(steps=[('sql', "SELECT left(translate(a.content, E'\\n\\t', '  '), 6) AS head FROM conversation_messages a "
                             "JOIN (SELECT session_id, turn_id FROM conversation_messages WHERE role = 'user' "
                             "AND content LIKE '%%%s%%' AND session_id = (SELECT session_id FROM conversation_messages "
                             "WHERE role = 'user' AND content LIKE '%%%s%%' ORDER BY created_at DESC LIMIT 1) "
                             "ORDER BY seq LIMIT 1) q ON q.session_id = a.session_id AND q.turn_id = a.turn_id "
                             "WHERE a.role = 'assistant' ORDER BY a.seq DESC LIMIT 1" % (STOP_Q, STOP_Q))],
              need='len(r[0]) > 0', check="r[0][0]['head'] == '[중단됨] '")},
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-368'], src=['CR-175'], pri='High'),

dict(id='IT-09-002', flow='다시 생성과 질문 수정', kind='정상', title='다시 생성하면 새 답이 오고 앞 답을 버전 번호로 볼 수 있으며, 서버 대화에는 마지막 버전만 이어진다',
     pre=WES_CS,
     steps='1. 질문을 보내고 답이 끝나면 답 아래 「다시 생성」을 누른다\n'
           '2. 버전 번호 옆 「이전」, 「다음」을 누른다\n'
           '3. 서버 대화 조회 GET /api/v1/conversations/{session_id} 로 그 세션의 메시지를 본다\n'
           '4. 테넌트 DB conversation_messages 에서 그 세션의 행을 본다',
     expect='1. 같은 질문의 새 답이 오고 답 아래 버전 번호 "2/2" 가 보인다\n'
            '2. "1/2" 에서는 앞 답 글이, "2/2" 에서는 새 답 글이 보인다\n'
            '3. 메시지 목록의 답은 새 답 하나뿐이고 그 turnId 가 다시 보낸 질문의 turn_id 와 같다\n'
            '4. 앞 답 행에 deleted_at 이 찍히고 delete_reason 이 REWIND 다\n'
            '5. 다시 보낸 질문과 새 답 행은 deleted_at 이 비어 있고 turn_id 가 서로 같다',
     judge=['합동', '합동', '서버', '서버', '서버'],
     probe={n: dict(steps=[ask(Q1), ask(Q1, session=S0, rewind='{val:0:data.turn_seq}'), s2], wait=180, check=c)
            for n, s2, c in ((3, ('api', WES, 'GET', '/api/v1/conversations/{val:0:data.session_id}'),
                              "[m['turnId'] for m in r[2]['body']['data']['messages'] if m['role'] == 'assistant'] == [r[1]['body']['data']['turn_id']]"),
                             (4, rows(0), f"len({answers_of(2, 0)}) > 0 and all(x['del'] == 't' and x['delete_reason'] == 'REWIND' for x in {answers_of(2, 0)})"),
                             (5, rows(0), "len([x for x in r[2] if x['del'] == 'f' and x['role'] == 'user']) == 1 "
                             "and len([x for x in r[2] if x['del'] == 'f' and x['role'] == 'assistant']) > 0 "
                             "and all(x['turn_id'] == [u for u in r[2] if u['del'] == 'f' and u['role'] == 'user'][0]['turn_id'] "
                             "for x in r[2] if x['del'] == 'f')"))},
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [4]},
     req=['PRD-369'], src=['CR-175'], pri='High'),

dict(id='IT-09-003', flow='다시 생성과 질문 수정', kind='정상', title='가운데 질문을 고쳐 보내면 경고를 보인 뒤 그 뒤 대화가 지워지고 고친 질문의 답이 이어진다',
     pre=WES_CS + '. 질문 셋을 주고받은 대화',
     steps='1. 첫째 질문의 「수정」을 누른다\n'
           '2. 글을 고쳐 전송한다\n'
           '3. 빈 입력칸에서 위 화살표를 누른다\n'
           '4. 테넌트 DB conversation_messages 에서 그 세션의 행을 본다',
     expect='1. 수정 칸 안에 "이 질문 뒤 대화는 지워집니다" 가 보인다\n'
            '2. 둘째, 셋째 질문과 답이 화면에서 사라지고 고친 질문과 그 답이 이어진다\n'
            '3. 고친 질문이 수정 칸으로 열린다\n'
            '4. 첫째 질문 묶음(앞에 붙은 화면 맥락 포함)부터 셋째 답까지의 행에 delete_reason REWIND 가 찍힌다\n'
            '5. 고친 질문과 그 답이 deleted_at 이 빈 행으로 저장되고 그 첫 seq 가 지운 첫째 질문 묶음의 첫 seq 와 같다',
     judge=['합동', '합동', '합동', '서버', '서버'],
     probe={n: dict(steps=[ask('시험 첫 질문입니다. 숫자 1 만 답해 주세요', context=CTX),
                      ask('시험 둘째 질문입니다. 숫자 2 만 답해 주세요', session=S0, context=CTX),
                      ask('시험 셋째 질문입니다. 숫자 3 만 답해 주세요', session=S0, context=CTX),
                      ask('고친 첫 질문입니다. 숫자 4 만 답해 주세요', session=S0, rewind='{val:0:data.turn_seq}', context=CTX),
                      rows(0)], wait=180, check=c)
            for n, c in ((4, f"len([x for x in r[4] if x['turn_id'] == {turn(0)} and x['role'] == 'system']) == 1 "
                             f"and len([x for x in r[4] if x['turn_id'] in ({turn(0)}, {turn(1)}, {turn(2)}) and x['role'] == 'assistant']) == 3 "
                             f"and all(x['del'] == 't' and x['delete_reason'] == 'REWIND' for x in r[4] if x['turn_id'] in ({turn(0)}, {turn(1)}, {turn(2)}))"),
                         (5, f"len({answers_of(4, 3)}) > 0 and all(x['del'] == 'f' for x in r[4] if x['turn_id'] == {turn(3)}) "
                             f"and min(int(x['seq']) for x in r[4] if x['turn_id'] == {turn(3)}) "
                             f"== min(int(x['seq']) for x in r[4] if x['turn_id'] == {turn(0)})"))},
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [4]},
     req=['PRD-369'], src=['CR-175'], pri='High'),

dict(id='IT-09-004', flow='다시 생성과 질문 수정', kind='예외', title='답이 도는 중에는 다시 생성과 수정 단추가 숨고, API 로 되감기를 보내면 409 로 거절된다',
     pre=WES_CS,
     steps='1. 질문을 보내고 답이 나오는 동안 앞 답과 앞 질문 아래를 본다\n'
           '2. 답이 나오는 동안 같은 session_id 와 앞 답의 turn_seq 를 rewind_from_seq 로 실어 POST /api/v1/chat/completions 를 보낸다',
     expect='1. 앞 답의 「다시 생성」과 앞 질문의 「수정」 단추가 보이지 않는다\n'
            '2. 409 이고 오류 글자가 session_busy 다',
     judge=['합동', '단위'],
     pair={1: [1], 2: [2]},
     req=['PRD-369'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-005', flow='다시 생성과 질문 수정', kind='예외', title='되감을 수 없는 순번으로 보낸 요청은 거절되고 대화 기록은 그대로다',
     pre='위젯 토큰(chat:stream) 또는 테넌트 키. 질문 둘을 주고받은 대화',
     steps='1. session_id 없이 rewind_from_seq 를 실어 대화 요청을 보낸다\n'
           '2. 답 메시지의 seq 를 rewind_from_seq 로 보낸다\n'
           '3. 대화 압축 경계보다 앞 질문의 seq 를 rewind_from_seq 로 보낸다\n'
           '4. 2, 3 뒤에 그 세션의 행을 본다',
     expect='1. 400 이고 오류 글자가 rewind_invalid:no_session 이다\n'
            '2. 400 이고 오류 글자가 rewind_invalid:not_turn_start 다\n'
            '3. 409 이고 오류 글자가 rewind_before_compaction 이다\n'
            '4. 새로 지운 표시가 찍힌 행이 없다',
     judge=['서버', '단위', '단위', '단위'],
     probe={1: dict(steps=[ask(Q1, session=None, rewind=0)], check=rejected(0, 400, 'rewind_invalid:no_session'))},
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-369'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-006', flow='응답 평가', kind='정상', title='좋아요와 아쉬워요(이유, 의견)를 남기고 바꾸고 취소하면 관리 화면 응답 평가에 그대로 보인다',
     pre=WES_CS + '. 답 둘이 있는 대화. 관리 화면은 CR-175 판이 개발기에 올라간 뒤',
     steps='1. 첫 답 아래 「좋아요」를 누른다\n'
           '2. 둘째 답 아래 「아쉬워요」를 누르고 이유 "조회를 안 함" 과 "너무 김" 을 고른 뒤 의견을 쓰고 「전송」을 누른다\n'
           '3. 관리 화면 「응답 평가」를 연다\n'
           '4. 둘째 답의 「좋아요」를 누르고 테넌트 DB message_feedback 에서 그 답의 행을 본다\n'
           '5. 첫 답의 「좋아요」를 다시 누르고 message_feedback 에서 그 답의 행을 본다\n'
           '6. 관리 화면 「응답 평가」를 새로고침한다',
     expect='1. 「좋아요」 단추가 켜진 상태로 바뀐다\n'
            '2. "어떤 점이 아쉬웠나요?" 아래 이유 칩과 의견 칸이 펼쳐지고, 전송 뒤 "의견을 보냈습니다" 가 보인다\n'
            '3. 두 행이 있고 둘째 답 행에 아쉬워요, 이유 "조회를 안 함, 너무 김", 쓴 의견, 모델 claude-sonnet-4-5 가 보인다\n'
            '4. 둘째 답의 행이 하나이고 rating 이 UP, reason_codes 가 빈 배열이다\n'
            '5. 첫 답의 행이 없다\n'
            '6. 둘째 답 한 행만 좋아요로 보인다',
     judge=['합동', '합동', '합동', '서버', '서버', '합동'],
     probe={4: dict(steps=[ask(Q1),
                   ('api', WES, 'PUT', '/api/v1/chat/feedback', dict(session_id=S0, turn_id='{val:0:data.turn_id}', rating='DOWN',
                                                                   reason_codes=['NO_LOOKUP', 'TOO_LONG'], comment='서버 판정 시험')),
                   ('api', WES, 'PUT', '/api/v1/chat/feedback', dict(session_id=S0, turn_id='{val:0:data.turn_id}', rating='UP', reason_codes=[])),
                   ('sql', "SELECT rating, reason_codes::text AS reasons FROM message_feedback WHERE turn_id = '{val:0:data.turn_id}'")],
              wait=180,
              check="r[1]['status'] == 200 and r[2]['status'] == 200 and len(r[3]) == 1 and r[3][0]['rating'] == 'UP' and loads(r[3][0]['reasons']) == []"),
           5: dict(steps=[ask(Q1),
                   ('api', WES, 'PUT', '/api/v1/chat/feedback', dict(session_id=S0, turn_id='{val:0:data.turn_id}', rating='UP', reason_codes=[])),
                   ('api', WES, 'DELETE', '/api/v1/chat/feedback?turn_id={val:0:data.turn_id}'),
                   ('sql', "SELECT count(*) AS n FROM message_feedback WHERE turn_id = '{val:0:data.turn_id}'")],
              wait=180,
              check="r[1]['status'] == 200 and r[2]['status'] == 200 and r[3][0]['n'] == '0'")},
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6]},
     req=['PRD-370', 'FE-043'], src=['CR-175'], pri='High'),

dict(id='IT-09-007', flow='응답 평가', kind='예외', title='규칙에 맞지 않는 평가는 거절되고, 저장에 실패하면 단추가 원래대로 돌아온다',
     pre=WES_CS + '. 런타임 설정 widget.feedback-comment-max-chars 500',
     steps='1. 아쉬워요 의견 칸에 501자를 쓴다\n'
           '2. rating UP 에 reason_codes 를 실어 PUT /api/v1/chat/feedback 를 보낸다\n'
           '3. 런타임 설정에 없는 code 를 reason_codes 로 보낸다\n'
           '4. 다른 사람 대화의 turn_id 로 보낸다\n'
           '5. 평가 저장 요청이 실패하는 상태에서 「좋아요」를 누른다\n'
           '6. 위젯 토큰으로 GET /api/v1/chat/feedback 를 부른다',
     expect='1. 칸 아래 "500자까지 입력할 수 있습니다" 가 보이고 「전송」이 눌리지 않는다\n'
            '2. 400 이고 오류 글자가 feedback_invalid:reason_codes 다\n'
            '3. 400 이고 오류 글자가 feedback_invalid:reason_codes 다\n'
            '4. 404 이고 오류 글자가 turn_not_found 다\n'
            '5. 단추가 누르기 전 상태로 돌아가고 "평가를 저장하지 못했습니다" 가 보인다\n'
            '6. 403 이고 오류 글자가 feedback_list_forbidden 이다',
     judge=['합동', '단위', '단위', '단위', '가상', '서버'],
     probe={6: dict(steps=[('api', WES, 'GET', '/api/v1/chat/feedback')], check=rejected(0, 403, 'feedback_list_forbidden'))},
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6]},
     req=['PRD-370'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-008', flow='모델 선택', kind='정상', title='연결에 넣은 허용 모델이 위젯 목록에 순서대로 보이고, 고른 모델로 답하며 답 아래에 그 이름이 보인다',
     pre=WES_CS,
     steps='1. 위젯 토큰으로 GET /api/v1/chat/widget-settings?connection_id=cli-runner-bp-wes-cs-001 을 부른다\n'
           '2. 위젯 입력칸 아래 모델 단추를 누른다\n'
           '3. "Opus 4.5" 를 고르고 질문을 보낸다\n'
           '4. 테넌트 DB conversation_messages 에서 그 답 행의 model 을 본다\n'
           '5. 로컬 위젯 견본 화면에서 허용 모델이 없는 연결로 위젯을 연다',
     expect='1. models 가 Opus 4.5, Sonnet 5.5, Sonnet 4.5, Haiku 5.5 순서이고 default_model 이 claude-sonnet-4-5 다\n'
            '2. 목록이 1 과 같은 순서로 보이고 처음 고른 모델이 "Sonnet 4.5" 다\n'
            '3. 답 아래 모델 이름이 "Opus 4.5" 다\n'
            '4. model 이 claude-opus-4-5 다\n'
            '5. 입력칸 아래 모델 단추가 없다',
     judge=['서버', '합동', '합동', '서버', '합동'],
     probe={1: dict(steps=[('api', WES, 'GET', '/api/v1/chat/widget-settings?connection_id=' + CONN)],
              check="r[0]['status'] == 200 and [m['label'] for m in r[0]['body']['data']['models']] == ['Opus 4.5', 'Sonnet 5.5', 'Sonnet 4.5', 'Haiku 5.5'] "
                    "and r[0]['body']['data']['default_model'] == 'claude-sonnet-4-5'"),
           4: dict(steps=[ask(Q1, model='claude-opus-4-5'), rows(0)], wait=180,
              check=f"len({answers_of(1, 0)}) > 0 and all(x['model'] == 'claude-opus-4-5' for x in {answers_of(1, 0)})")},
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-371'], src=['CR-175'], pri='High'),

dict(id='IT-09-009', flow='모델 선택', kind='정상', title='관리 화면 연결 편집의 허용 모델 칸에서 넣고 순서를 바꾸고 비운 값이 연결 설정과 위젯에 그대로 반영된다',
     pre='관리 화면 「연결 관리」. 공용 WES 연결이 아닌 시험용 연결 하나이고, 그 연결의 기본 모델은 claude-sonnet-4-5 다'
         '(기본 모델이 허용 모델 목록에 없으면 저장이 막힌다). 관리 화면은 CR-175 판이 올라간 뒤',
     steps='1. 시험용 연결 편집의 「허용 모델」에 claude-sonnet-4-5(Sonnet 4.5), claude-haiku-5-5(Haiku 5.5) 를 넣고 저장한 뒤 편집을 다시 연다\n'
           '2. 둘째 줄을 「위로」 올려 저장하고 테넌트 DB connections 의 config 를 본다\n'
           '3. 그 연결로 위젯을 열고 모델 단추를 누른다\n'
           '4. 「허용 모델」을 모두 지워 저장하고 config 를 본다',
     expect='1. 두 줄이 넣은 순서대로 보인다\n'
            '2. config.models 가 Haiku 5.5, Sonnet 4.5 순서의 두 원소다\n'
            '3. 위젯 목록이 Haiku 5.5, Sonnet 4.5 순서로 보인다\n'
            '4. config 에 models 키가 없다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['FE-042'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-010', flow='모델 선택', kind='예외', title='허용 목록에 없는 모델로 보낸 대화 요청은 400 으로 거절되고, auto 는 연결 기본 모델로 답한다',
     pre=WES_CS + '. 위젯 토큰(chat:stream)',
     steps='1. connection_id cli-runner-bp-wes-cs-001, model claude-opus-5-5 로 POST /api/v1/chat/completions 를 보낸다\n'
           '2. 같은 연결에 model auto 로 보낸다',
     expect='1. 400 이고 오류 글자가 model_not_allowed 다\n'
            '2. 200 이고 done 의 model 이 claude-sonnet-4-5 다',
     judge=['서버', '서버'],
     probe={1: dict(steps=[ask(Q1, model='claude-opus-5-5')], check=rejected(0, 400, 'model_not_allowed')),
           2: dict(steps=[ask(Q1, model='auto', stream=True)], wait=180,
              check="r[0]['status'] == 200 and 'event:done' in r[0]['body'] "
                    "and loads(r[0]['body'].split('event:done', 1)[1].split('data:', 1)[1].split('\\n', 1)[0])['model'] == 'claude-sonnet-4-5'")},
     pair={1: [1], 2: [2]},
     req=['PRD-371'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-011', flow='깊게 생각하기', kind='정상', title='깊게 생각하기를 켜면 생각 접은 줄이 생기고, 끄면 생기지 않으며, 지원하지 않는 연결에서는 단추가 없다',
     pre=WES_CS,
     steps='1. 위젯을 연다\n'
           '2. 「깊게 생각하기」가 켜진 채 질문을 보낸다\n'
           '3. 생각 접은 줄을 눌러 펼친다\n'
           '4. 「깊게 생각하기」를 끄고 질문을 보낸다\n'
           '5. 로컬 위젯 견본 화면에서 요청마다 생각을 바꾸지 못하는 연결로 위젯을 연다',
     expect='1. 「깊게 생각하기」 단추가 켜진 상태로 보인다\n'
            '2. 답 위에 "n초 동안 생각했습니다" 접은 줄이 생긴다\n'
            '3. 그 질문을 두고 생각한 글이 보인다\n'
            '4. 새 답에는 생각 접은 줄이 생기지 않는다\n'
            '5. 「깊게 생각하기」 단추가 없다\n'
            '6. 켜고 끌 때 실행기가 작업자를 다시 띄우지 않고 생각 한도 신호만 보낸다',
     judge=['합동', '합동', '합동', '합동', '합동', '단위'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [2, 4]},
     req=['PRD-371'], src=['CR-175'], pri='High'),

dict(id='IT-09-012', flow='깊게 생각하기', kind='예외', title='thinking 칸에 정해진 값 밖을 보내거나 지원하지 않는 연결에 켜기를 보내면 400 으로 거절된다',
     pre='위젯 토큰(chat:stream). 요청마다 생각을 바꾸지 못하는 연결 하나',
     steps='1. thinking 을 maybe 로 실어 대화 요청을 보낸다\n'
           '2. 요청마다 생각을 바꾸지 못하는 연결에 thinking on 으로 보낸다',
     expect='1. 400 이고 오류 글자가 thinking_invalid 다\n'
            '2. 400 이고 오류 글자가 thinking_not_supported 다',
     judge=['서버', '서버'],
     probe={1: dict(steps=[ask(Q1, thinking='maybe')], check=rejected(0, 400, 'thinking_invalid')),
           2: dict(steps=[('sql', "SELECT id FROM connections WHERE adapter = 'openai_compatible' ORDER BY id LIMIT 1"),
                          ('api', WES, 'POST', CHAT, dict(connection_id='{val:0:id}', stream=False, thinking='on',
                                                          messages=[dict(role='user', content=Q1)]))],
              need='len(r[0]) > 0', check=rejected(1, 400, 'thinking_not_supported'))},
     pair={1: [1], 2: [2]},
     req=['PRD-371'], src=['CR-175'], pri='Low'),

dict(id='IT-09-013', flow='후속 질문과 제목', kind='정상', title='첫 답 뒤 후속 질문 칩과 대화 제목이 생기고, 칩을 누르면 바로 묻고, 사람이 고친 제목은 바뀌지 않는다',
     pre=WES_CS + '. 런타임 설정 widget.follow-ups-enabled true, widget.follow-ups-count 3',
     steps='1. 「새 대화」를 누르고 질문을 보내 답이 끝날 때까지 본다\n'
           '2. 머리줄 제목을 본다\n'
           '3. 칩 하나를 누른다\n'
           '4. 테넌트 DB conversation_sessions 에서 그 세션의 title, title_source 를 본다\n'
           '5. 제목을 사람이 고친(title_source USER) 세션에서 질문한다\n'
           '6. 후속 질문 호출 뒤 그 세션의 대화 기록 수를 본다',
     expect='1. 답 아래 칩 3개가 그 답 내용에 이어지는 질문으로 보인다\n'
            '2. "새 대화" 가 그 대화 내용을 담은 제목으로 바뀐다\n'
            '3. 칩의 글이 질문으로 바로 보내지고 앞 칩들이 사라진다\n'
            '4. title_source 가 GENERATED 이고 title 이 머리줄 제목과 같다\n'
            '5. 세션 제목이 고친 그대로다\n'
            '6. 후속 질문 호출의 질문과 답이 대화 기록에 더해지지 않는다',
     judge=['합동', '합동', '합동', '서버', '단위', '단위'],
     probe={4: dict(steps=[ask('창고 재고 실사는 왜 하는지 한 문장으로 답해 주세요'),
                   ('api', WES, 'POST', '/api/v1/chat/sessions/{val:0:data.session_id}/follow-ups',
                    dict(turn_id='{val:0:data.turn_id}', connection_id=CONN)),
                   ('sql', "SELECT title, title_source FROM conversation_sessions WHERE session_id = '{val:0:data.session_id}'")],
              wait=180,
              check="r[1]['status'] == 200 and len(r[2]) == 1 and r[2][0]['title_source'] == 'GENERATED' "
                    "and len(r[2][0]['title'] or '') > 0 and r[2][0]['title'] == r[1]['body']['data'].get('title')")},
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5], 6: [6]},
     req=['PRD-372'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-014', flow='후속 질문과 제목', kind='예외', title='후속 질문을 끄거나 만들기가 실패해도 답은 그대로 오고 칩만 보이지 않는다',
     pre=WES_CS + '. 런타임 설정 widget.follow-ups-enabled 는 공용 설정이라 끄기 전에 승인받고, 끝나면 true 로 되돌린다',
     steps='1. widget.follow-ups-enabled 를 false 로 두고 위젯을 다시 열어 질문한다\n'
           '2. 같은 설정에서 POST /api/v1/chat/sessions/{session_id}/follow-ups 를 부른다\n'
           '3. 후속 질문 모델 답이 JSON 이 아니거나 widget.follow-ups-timeout-ms 를 넘기게 하고 부른다',
     expect='1. 답이 끝까지 오고 칩이 보이지 않는다\n'
            '2. 409 이고 오류 글자가 follow_ups_disabled 다\n'
            '3. 502 이고 오류 글자가 follow_ups_failed 다',
     judge=['합동', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-372'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-015', flow='첫 화면', kind='정상', title='붙이는 앱이 준 안내와 추천 질문이 첫 화면에 보이고, 누르면 바로 묻는다',
     pre='로컬 위젯 견본 화면. suggestions 두 개, empty-title, empty-subtitle 을 준 화면과 아무것도 주지 않은 화면, suggestions 다섯 개를 준 화면',
     steps='1. 옵션을 준 화면에서 새 대화 첫 화면을 본다\n'
           '2. 추천 질문 하나를 누른다\n'
           '3. suggestions 다섯 개를 준 화면을 연다\n'
           '4. 옵션을 주지 않은 화면을 연다',
     expect='1. 준 안내 제목, 안내 한 줄, 추천 질문 두 개가 보인다\n'
            '2. 그 글이 질문으로 바로 보내지고 답이 온다\n'
            '3. 추천 질문이 앞의 네 개만 보인다\n'
            '4. 안내 제목 "무엇을 도와드릴까요?" 가 보이고 추천 질문 칩이 없다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-373'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-016', flow='읽기 편의', kind='정상', title='긴 답이 나오는 중 위로 올려 읽으면 끌려 내려가지 않고 맨 아래로 단추가 뜬다',
     pre=WES_CS,
     steps='1. 긴 답이 나올 질문을 보내고 글이 나오는 중 대화 칸을 위로 올린다\n'
           '2. 「맨 아래로」를 누른다\n'
           '3. 맨 아래에 둔 채 답이 끝날 때까지 본다',
     expect='1. 읽던 자리에 머물고 "맨 아래로" 단추가 뜬다\n'
            '2. 맨 아래로 내려가고 "맨 아래로" 단추가 사라진다\n'
            '3. 새 글이 나올 때마다 따라 내려간다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-374'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-017', flow='읽기 편의', kind='정상', title='입력칸이 여러 줄로 늘어나고, 한글 조합 중 Enter 로 보내지지 않으며, 빈 칸에서 위 화살표로 마지막 질문을 고친다',
     pre=WES_CS + '. 질문 하나를 주고받은 대화',
     steps='1. 입력칸에 Shift+Enter 로 다섯 줄을 쓴다\n'
           '2. 한글을 치다가 조합이 끝나기 전에 Enter 를 누른다\n'
           '3. 조합이 끝난 뒤 Enter 를 누른다\n'
           '4. 빈 입력칸에서 위 화살표를 누른다',
     expect='1. 입력칸이 줄 수만큼 높아지고 줄바꿈이 들어간다\n'
            '2. 질문이 보내지지 않고 입력칸에 글이 남는다\n'
            '3. 질문이 보내진다\n'
            '4. 마지막 질문이 수정 칸으로 열린다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-374'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-018', flow='겉모습', kind='정상', title='글자 크기를 바꾸면 본문과 입력칸이 함께 바뀌고 새로고침 뒤에도 남으며, 사람이 고른 값이 붙이는 앱 기본값보다 앞선다',
     pre=WES_CS + '. 로컬 위젯 견본 화면에 font-size small 을 준 화면',
     steps='1. 더보기의 「글자 크기」에서 「크게」를 고른다\n'
           '2. 페이지를 새로고침하고 위젯을 연다\n'
           '3. 기억한 값이 없는 브라우저(시크릿 창)로 font-size small 을 준 화면을 연다\n'
           '4. 그 화면에서 「보통」을 고르고 새로고침한다',
     expect='1. 답 글과 입력칸 글자가 함께 커진다\n'
            '2. 글자가 「크게」 그대로다\n'
            '3. 글자가 「작게」로 시작한다\n'
            '4. 글자가 「보통」 그대로다',
     judge=['합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-375'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-019', flow='읽기 편의', kind='정상', title='새 대화를 누르면 화면이 비고 새 세션으로 묻고, 앞 대화는 세션 목록에 남는다',
     pre=WES_CS + '. 질문 하나를 주고받은 대화',
     steps='1. 머리줄 「새 대화」를 누른다\n'
           '2. 질문을 보내고 테넌트 DB conversation_messages 에서 그 질문 행의 session_id 를 본다\n'
           '3. 관리 화면 「세션」 목록을 본다',
     expect='1. 앞 질문과 답이 화면에서 사라지고 백오피스2 가 준 "AI 에게 질문" 안내 제목이 보인다\n'
            '2. session_id 가 앞 대화와 다르다\n'
            '3. 앞 대화 세션이 목록에 남아 있다',
     judge=['합동', '서버', '합동'],
     probe={2: dict(steps=[ask(Q1), ask('시험 질문입니다. 숫자 2 만 답해 주세요'),
                   ('sql', "SELECT DISTINCT session_id FROM conversation_messages WHERE role = 'user' "
                           "AND turn_id IN ('{val:0:data.turn_id}', '{val:1:data.turn_id}')")],
              wait=180, check="len(r[2]) == 2")},
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-374'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-020', flow='읽기 편의', kind='정상', title='크게 보기는 화면 가득 뜨고 Esc 로 닫히며, 복사는 답 원문을 담고, 질문과 답에 보낸 시각이 보인다',
     pre=WES_CS + '. 표나 목록이 든 답이 있는 대화',
     steps='1. 머리줄 「크게 보기」를 누른다\n'
           '2. Esc 를 누른다\n'
           '3. 표가 든 답 아래 「복사」를 누른다\n'
           '4. 메모장에 붙여넣는다\n'
           '5. 오늘 보낸 질문과 답 옆을 본다',
     expect='1. 위젯이 페이지 위로 화면 가득 겹쳐 보인다\n'
            '2. 원래 자리 크기로 돌아간다\n'
            '3. "복사했습니다" 가 잠깐 보인다\n'
            '4. 붙여넣은 글이 답의 마크다운 원문(표의 | 줄, 목록 기호)이다\n'
            '5. 질문과 답마다 시:분 시각이 보인다',
     judge=['합동', '합동', '합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-374'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-021', flow='읽기 편의', kind='정상', title='도구를 쓴 답은 도구 사용이 접은 줄로 보이고 펼치면 도구 이름과 입력, 결과 앞부분이 보인다',
     pre=WES_CS,
     steps='1. 조회가 필요한 질문("이 문의의 송장번호 출고 이력을 조회해 주세요")을 보내고 답이 나오는 중 본다\n'
           '2. 답이 끝난 뒤 본다\n'
           '3. 접은 줄을 펼친다',
     expect='1. "도구를 사용하는 중입니다" 접은 줄이 보인다\n'
            '2. 접은 줄이 "도구를 n번 사용했습니다" 로 바뀐다\n'
            '3. 쓴 도구 이름과 입력 앞부분, 결과 앞부분이 보인다',
     judge=['합동', '합동', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-374'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-022', flow='겉모습', kind='정상', title='위젯이 실화면 목업 개선안과 같은 무채색과 선 아이콘으로 보인다',
     pre=WES_CS + '. CR-175 실화면 목업 「개선안」 캡처',
     steps='1. 질문 하나를 주고받은 위젯을 목업 「개선안」 캡처와 나란히 놓고 본다',
     expect='1. 머리줄, 말풍선, 단추, 입력칸의 색과 아이콘이 목업과 같다',
     judge=['합동'],
     pair={1: [1]},
     req=['PRD-375'], src=['CR-175'], pri='Low'),

dict(id='IT-09-023', flow='모델 선택', kind='정상', title='CLI 연결에서 모델을 바꾼 뒤에도 새로 뜬 실행기 작업자가 방금 보낸 질문에 답한다(2026-11-11 결함 회귀)',
     pre=WES_CS + '. 실행기가 2026-11-11 수정판',
     steps='1. Sonnet 4.5 로 질문 둘을 주고받는다\n'
           '2. 모델을 "Opus 4.5" 로 바꾸고 "고객에게 보낼 첫 인사 한 줄만 써 주세요" 를 보낸다\n'
           '3. 모델을 "Haiku 5.5" 로 바꾸고 같은 질문을 보낸다\n'
           '4. 테넌트 DB conversation_messages 에서 2, 3 의 답 행을 본다',
     expect='1. 2 의 답이 인사 한 줄이고 1 의 질문에 대한 답이 아니다\n'
            '2. 3 의 답이 인사 한 줄이고 1 의 질문에 대한 답이 아니다\n'
            '3. 2 의 답 행 model 이 claude-opus-4-5, 3 의 답 행 model 이 claude-haiku-5-5 다\n'
            '4. 새로 뜬 작업자는 지난 질문과 답을 이번 질문 앞에 붙인 user 줄 하나로 받는다',
     judge=['합동', '합동', '서버', '단위'],
     probe={3: dict(steps=[ask('시험 첫 질문입니다. 숫자 1 만 답해 주세요'),
                   ask('시험 둘째 질문입니다. 숫자 2 만 답해 주세요', session=S0),
                   ask('고객에게 보낼 첫 인사 한 줄만 써 주세요', model='claude-opus-4-5', session=S0),
                   ask('고객에게 보낼 첫 인사 한 줄만 써 주세요', model='claude-haiku-5-5', session=S0),
                   rows(0)], wait=180,
              check=f"len({answers_of(4, 2)}) > 0 and all(x['model'] == 'claude-opus-4-5' for x in {answers_of(4, 2)}) "
                    f"and len({answers_of(4, 3)}) > 0 and all(x['model'] == 'claude-haiku-5-5' for x in {answers_of(4, 3)})")},
     pair={1: [2], 2: [3], 3: [4], 4: [2, 3]},
     req=['PRD-371'], src=['CR-175'], pri='High'),

dict(id='IT-09-024', flow='읽기 편의', kind='정상', title='칸 안에 넣은 위젯은 대화가 길어져도 칸 높이를 넘지 않고 대화만 칸 안에서 스크롤된다(구현 중 고친 결함 회귀)',
     pre='로컬 위젯 견본 화면. 높이 440px 칸에 display inline 으로 넣은 위젯',
     steps='1. 질문 다섯 개를 주고받는다\n'
           '2. 대화 칸을 위아래로 굴린다',
     expect='1. 위젯이 440px 칸 밖으로 늘어나지 않고 입력칸이 칸 아래쪽에 보인다\n'
            '2. 머리줄과 입력칸은 그대로 있고 대화만 칸 안에서 움직인다',
     judge=['합동', '합동'],
     pair={1: [1], 2: [2]},
     req=['PRD-374'], src=['CR-175'], pri='Medium'),

dict(id='IT-09-025', flow='위젯 파일 내주기', kind='정상', title='배포한 위젯 수정이 강력 새로고침 없이 이미 위젯을 열어 본 사용자 브라우저에도 닿는다(합동시험 2차 뒤 고친 결함 회귀)',
     pre='위젯 파일을 내주는 aimbase api. 3 은 로컬 위젯 견본 화면(허용 출처 localhost:3999)',
     steps='1. 로더(aimbase-chat.umd.global.js), esm(aimbase-chat.esm.js), 타입(aimbase-chat.d.ts), 안내(guide.html)를 받는다\n'
           '2. 이름 끝에 내용 해시 8자가 붙은 코드 조각을 받는다\n'
           '3. 위젯 허용 출처 화면에서 로더를 주소 끝에 ?v=t175 를 붙여 불러오고 위젯 파일 요청을 본다',
     expect='1. 네 파일 응답의 Cache-Control 이 no-cache 여서 브라우저가 열 때마다 바뀌었는지 묻는다\n'
            '2. 코드 조각 응답의 Cache-Control 이 max-age=31536000, public, immutable 이다\n'
            '3. esm 요청 주소가 aimbase-chat.esm.js?v=t175 로 로더와 같은 표시를 달고, aimbase-chat 요소가 정의된다',
     judge=['단위', '단위', '합동'],
     pair={1: [1], 2: [2], 3: [3]},
     src=['CR-175'], pri='Medium'),

]
