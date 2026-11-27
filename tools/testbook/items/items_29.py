# 영역 29 대화 일괄 완전 삭제와 보관 기간 시험항목. 근거는 CR-204 문서 1.3 규칙과 3절 서버다.
# 대상 기능은 T1-1 PRD-650, 651 이라 req 에 그 번호를 적고 src 에 CR 번호를 적는다.
# 화면이 없어 전부 서버 판정이다. 로컬 api 와 로컬 DB(5432)에서 돈다.

LOCAL = '로컬 api(8280), 로컬 테넌트 DB, 테넌트 API 키 하나'
SEED = ('사용자 tb:office-1:u1 의 대화 2(첨부 1, 만든 그림 1, 응답 평가 1, 삭제 표시된 대화 1 포함), '
        '사용자 tb:office-1:u2 의 대화 1, 사용자 tb:office-2:u1 의 대화 1, '
        '함께 보기 키 tb:case:office-1:/case/[id]:7 의 대화 1(u1 과 u2 가 한 번씩 질문)')

ITEMS = [

dict(id='IT-29-001', flow='조건 검증', kind='예외', title='조건이 없거나 글자 규칙에 어긋나는 요청은 400 으로 거절한다',
     pre=LOCAL,
     steps='1. 본문 {} 로 일괄 완전 삭제를 부른다\n'
           '2. share_key 에 공백이 든 값으로 부른다\n'
           '3. share_key_prefix 를 빈 글자로 부른다\n'
           '4. limit 0 으로 부른다',
     expect='1. 400 purge_invalid:criteria\n'
            '2. 400 purge_invalid:share_key\n'
            '3. 400 purge_invalid:share_key_prefix\n'
            '4. 400 purge_invalid:limit',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-650', 'BIZ-294'], src=['CR-204'], pri='High'),

dict(id='IT-29-002', flow='권한', kind='예외', title='위젯 토큰과 관리자가 아닌 로그인으로는 부를 수 없다',
     pre=LOCAL + '. 위젯 토큰 하나, 역할 user 로그인 하나',
     steps='1. 위젯 토큰으로 user_ref 조건을 부른다\n'
           '2. 역할 user 로그인으로 부른다\n'
           '3. 테넌트 API 키로 dry_run 을 부른다',
     expect='1. 403 widget_forbidden\n'
            '2. 403\n'
            '3. 200',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-650', 'BIZ-296'], src=['CR-204'], pri='High'),

dict(id='IT-29-003', flow='미리 보기', kind='정상', title='dry_run 은 건수만 돌려주고 아무것도 지우지 않는다',
     pre=LOCAL + '. ' + SEED,
     steps='1. user_ref_prefix tb:office-1: 로 dry_run true 를 부른다\n'
           '2. 대화 행 수와 감사 기록 수를 본다',
     expect='1. 200, dry_run true, sessions.matched 4(삭제 표시된 대화와 함께 보는 대화 포함), users.matched 2, deleted 0\n'
            '2. 1번 전과 같고 conversation_purge 감사 기록이 늘지 않았다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-650', 'BIZ-294', 'BIZ-296'], src=['CR-204'], pri='High'),

dict(id='IT-29-004', flow='사건 대화 삭제', kind='정상', title='함께 보기 키 하나를 주면 그 대화만 DB 와 저장소에서 사라진다',
     pre=LOCAL + '. ' + SEED,
     steps='1. share_key tb:case:office-1:/case/[id]:7 로 부른다\n'
           '2. 그 대화 번호로 메시지, 평가, 요약, 추적 기록 행 수를 본다\n'
           '3. 다른 사용자 대화 수를 본다',
     expect='1. 200, sessions.deleted 1, remaining 0, completed true, users null\n'
            '2. 대화 행과 모든 표의 행이 0\n'
            '3. 1번 전과 같다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-650', 'BIZ-292'], src=['CR-204'], pri='High'),

dict(id='IT-29-005', flow='사용자 삭제', kind='정상', title='사용자 하나를 주면 그 사람의 대화와 파일과 자료가 모두 사라지고 함께 보는 대화에는 이름만 지워진다',
     pre=LOCAL + '. ' + SEED + '. u1 의 선호와 자동 기억 각 1',
     steps='1. user_ref tb:office-1:u1 로 부른다\n'
           '2. u1 의 대화, 첨부 파일, 그림 파일, 선호, 기억, 평가를 본다\n'
           '3. 함께 보는 대화의 u1 질문 행 display.asker 를 본다\n'
           '4. u2 의 대화 수를 본다',
     expect='1. 200, sessions.deleted 2(삭제 표시된 대화 포함), remaining 0, files.deleted 2, users.preferences 1, memories 1, feedback 1, asker_names_cleared 1, completed true\n'
            '2. 행 0, 저장소 파일 없음\n'
            '3. user_ref 는 남고 user_name 키가 없다\n'
            '4. 1번 전과 같다',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-650', 'BIZ-292', 'BIZ-293'], src=['CR-204'], pri='High'),

dict(id='IT-29-006', flow='사무소 묶음 삭제', kind='정상', title='두 접두어를 한 요청에 싣으면 그 사무소의 대화만 모두 사라지고 다른 사무소는 남는다',
     pre=LOCAL + '. ' + SEED,
     steps='1. user_ref_prefix tb:office-1: 와 share_key_prefix tb:case:office-1: 로 부른다\n'
           '2. tb:office-1 로 시작하는 대화와 함께 보는 대화의 행 수를 본다\n'
           '3. tb:office-2:u1 의 대화 수를 본다',
     expect='1. 200, sessions.deleted 4, remaining 0, users.matched 2, completed true\n'
            '2. 0\n'
            '3. 1번 전과 같다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-650', 'BIZ-294'], src=['CR-204'], pri='High'),

dict(id='IT-29-007', flow='반복 호출', kind='정상', title='상한보다 많으면 남은 수를 돌려주고 같은 본문으로 다시 부르면 끝까지 지운다',
     pre=LOCAL + '. 사용자 tb:office-3:u1 의 대화 5',
     steps='1. user_ref tb:office-3:u1, limit 2 로 부른다\n'
           '2. 같은 본문으로 다시 부른다\n'
           '3. 같은 본문으로 다시 부른다\n'
           '4. 한 번 더 부른다',
     expect='1. deleted 2, remaining 3, completed false, users null\n'
            '2. deleted 2, remaining 1, completed false\n'
            '3. deleted 1, remaining 0, completed true, users 채움\n'
            '4. matched 0, deleted 0, completed true',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-650', 'BIZ-294'], src=['CR-204'], pri='Medium'),

dict(id='IT-29-008', flow='감사 기록', kind='정상', title='삭제 호출마다 조건과 건수가 감사 기록에 남는다',
     pre=LOCAL + '. 사용자 tb:office-4:u1 의 대화 1',
     steps='1. user_ref tb:office-4:u1 로 부른다\n'
           '2. 감사 기록 conversation_purge 의 마지막 행을 본다',
     expect='1. 200, deleted 1\n'
            '2. 호출자 system-(키 id), 상세에 criteria 와 sessions.deleted 1',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-650', 'BIZ-296'], src=['CR-204'], pri='Medium'),

dict(id='IT-29-009', flow='자동 완전 삭제', kind='정상', title='삭제 표시된 뒤 보관 기간이 지난 대화와 되감긴 질문만 주기가 완전 삭제한다',
     pre=LOCAL + '. 삭제 표시 31일 전 대화 1, 삭제 표시 1일 전 대화 1, 살아 있는 대화에 31일 전 되감긴 질문과 답 2',
     steps='1. 자동 완전 삭제 주기를 한 번 돌린다(런타임 설정 chat.purge.retention-days 30)\n'
           '2. 31일 전 대화의 행을 본다\n'
           '3. 1일 전 대화의 행을 본다\n'
           '4. 살아 있는 대화의 메시지 행을 본다',
     expect='1. 로그에 테넌트와 삭제 수 1\n'
            '2. 대화와 메시지 행 0\n'
            '3. 그대로(삭제 표시만)\n'
            '4. 되감긴 2행이 없고 살아 있는 행은 그대로',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-651', 'BIZ-295'], src=['CR-204'], pri='Medium'),

]
