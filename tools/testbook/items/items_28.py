# 영역 28 위젯 지식 자료 토큰 결합 시험항목. 근거는 CR-206 문서 1.3 규칙과 3절 서버다.
# 대상 기능은 T1-1 PRD-648, PRD-649(PRD-001 보정)라 req 에 그 번호를 적고 src 에 CR 번호를 적는다.
# 서버 판정 방식(probe)은 시험준비 단계에서 채운다.

LOCAL = ('로컬 Aimbase api(bootRun). 시험 테넌트에 위젯 API 키 하나, 연결 하나, 지식 자료 셋(tb-office-a, tb-office-b, tb-common)이 있고 '
         'tb-office-a 와 tb-office-b 에 서로 다른 글 한 건씩이 들어 있다. '
         '허용 목록 토큰은 rag_source_ids [tb-office-a, tb-common] 으로 받고, 목록 없는 토큰은 칸 없이 받는다')

ITEMS = [

dict(id='IT-28-001', flow='토큰 발급 칸', kind='정상', title='토큰 발급에 허용 자료 목록을 실을 수 있고 테넌트에 없는 자료는 거절된다',
     pre=LOCAL,
     steps='1. rag_source_ids [tb-office-a, tb-common] 으로 위젯 토큰을 받고 토큰 내용을 본다\n'
           '2. rag_source_ids [tb-office-a, "", tb-office-a, tb-common] 으로 받는다\n'
           '3. rag_source_ids [tb-office-a, tb-nowhere] 로 받는다\n'
           '4. rag_source_ids 를 21개로 받는다\n'
           '5. 칸 없이 받는다',
     expect='1. 토큰 claim rag_source_ids 가 [tb-office-a, tb-common] 이다\n'
            '2. 발급되고 claim 은 [tb-office-a, tb-common] 이다(빈 값과 중복을 뺌)\n'
            '3. 400 widget_token_invalid:rag_source_id\n'
            '4. 400 widget_token_invalid:rag_source_id\n'
            '5. 발급되고 토큰에 rag_source_ids 가 없다',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-648', 'BIZ-290'], src=['CR-206'], pri='High'),

dict(id='IT-28-002', flow='대화 요청 자료 대조', kind='예외', title='허용 목록 토큰의 대화 요청은 목록 안의 자료만 찾고 목록 밖은 응답을 시작하기 전에 거절된다',
     pre=LOCAL,
     steps='1. 허용 목록 토큰으로 rag_source_id tb-office-a 를 실어 그 자료의 글을 묻는다\n'
           '2. 허용 목록 토큰으로 rag_source_id tb-office-b 를 실어 묻는다\n'
           '3. 허용 목록 토큰으로 rag_source_id 없이 tb-office-a 의 글을 묻는다\n'
           '4. 목록 없는 토큰으로 rag_source_id tb-office-b 를 실어 묻는다\n'
           '5. 목록 없는 토큰으로 rag_source_id tb-nowhere 를 실어 묻는다',
     expect='1. 200. done 의 citations 에 source_id tb-office-a 만 있다\n'
            '2. 404 rag_source_not_found. 스트림이 열리지 않는다\n'
            '3. 200. citations 에 source_id tb-office-a 가 있다(기본 자료)\n'
            '4. 200. citations 에 source_id tb-office-b 가 있다(지금과 같다)\n'
            '5. 404 rag_source_not_found',
     judge=['서버', '서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4], 5: [5]},
     req=['PRD-648', 'PRD-001', 'BIZ-290'], src=['CR-206'], pri='High'),

dict(id='IT-28-003', flow='청크 원문 조회', kind='예외', title='허용 목록 토큰은 목록 안 자료의 청크 원문만 읽는다',
     pre=LOCAL + '. IT-28-002 1번 응답의 citations 에서 tb-office-a 청크 ID 를, 목록 없는 토큰의 응답에서 tb-office-b 청크 ID 를 얻어 둔다',
     steps='1. 허용 목록 토큰으로 GET /api/v1/knowledge-sources/tb-office-a/chunks/{청크} 를 부른다\n'
           '2. 허용 목록 토큰으로 GET /api/v1/knowledge-sources/tb-office-b/chunks/{청크} 를 부른다\n'
           '3. 목록 없는 토큰으로 2번 주소를 부른다',
     expect='1. 200. 청크 원문이 온다\n'
            '2. 404 rag_source_not_found\n'
            '3. 200',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-648', 'BIZ-290'], src=['CR-206'], pri='High'),

dict(id='IT-28-004', flow='지식 자료 API 위젯 제한', kind='예외', title='위젯 토큰은 지식 자료 목록, 상세, 검색을 부르지 못하고 로그인은 그대로다',
     pre=LOCAL + '. 관리자 로그인 토큰 하나',
     steps='1. 위젯 토큰으로 GET /api/v1/knowledge-sources 를 부른다\n'
           '2. 위젯 토큰으로 GET /api/v1/knowledge-sources/tb-office-b 를 부른다\n'
           '3. 위젯 토큰으로 POST /api/v1/knowledge-sources/search {source_id: tb-office-b, query} 를 부른다\n'
           '4. 로그인 토큰으로 1번과 3번을 부른다',
     expect='1. 403 widget_forbidden\n'
            '2. 403 widget_forbidden\n'
            '3. 403 widget_forbidden\n'
            '4. 200',
     judge=['서버', '서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3], 4: [4]},
     req=['PRD-649', 'BIZ-291'], src=['CR-206'], pri='High'),

]
