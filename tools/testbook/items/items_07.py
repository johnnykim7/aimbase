# 영역 07 지식소스 문서 넣기와 검색 시험항목. 근거는 CR-170(T3-2 지식소스 API 와 검색 응답, T3-5 TC-RAG-006 ~ 011, TC-PY-RAG-008 ~ 014)이다.
# 부르는 쪽은 AXOPM 서버라 그 테넌트 키로 본다. 시험용 지식소스를 따로 만들어 쓰고 끝나면 지운다. 공용 이슈 지식소스는 쓰지 않는다.

ITEMS = [

dict(id='IT-07-001', flow='지식소스 문서 넣기', kind='정상', title='같은 지식소스에 문서 둘을 넣으면 둘 다 남는다',
     pre='Aimbase 테넌트 API 키, 사이드카(rag-pipeline)가 떠 있는 상태, 시험용 지식소스 1개(type "api")',
     steps='1. documentId "it07-doc-a", 반품 접수 오류 글로 텍스트를 넣는다(POST /api/v1/knowledge-sources/{id}/ingest-text)\n'
           '2. documentId "it07-doc-b", 반품 화면 문구 오류 글로 텍스트를 넣는다\n'
           '3. sourceId 를 그 지식소스로, query "반품 오류", topK 5 로 검색한다(POST /api/v1/knowledge-sources/search)',
     expect='1. 두 넣기 모두 200, success true\n'
            '2. 검색 결과에 documentId it07-doc-a 와 it07-doc-b 가 모두 있다',
     judge=['서버', '서버'],
     pair={1: [1, 2], 2: [3]},
     req=['PRD-052'], src=['CR-170'], pri='High'),

dict(id='IT-07-002', flow='지식소스 문서 넣기', kind='정상', title='같은 문서 ID 로 다시 넣으면 그 문서만 바뀌고 다른 문서는 남는다',
     pre='IT-07-001 을 마친 지식소스',
     steps='1. documentId "it07-doc-a" 를 배송 지연 안내 글로 다시 넣는다\n'
           '2. query "반품 오류" 로 검색한다\n'
           '3. query "배송 지연" 으로 검색한다',
     expect='1. 200, success true\n'
            '2. 결과에 it07-doc-b 가 있고, 반품 접수 오류 글은 없다\n'
            '3. 배송 지연 안내 글이 documentId it07-doc-a 로 나온다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-052'], src=['CR-170'], pri='High'),

dict(id='IT-07-003', flow='지식소스 동기화', kind='정상', title='문서 넣기로만 채운 api 유형 지식소스는 동기화해도 문서가 남는다',
     pre='IT-07-002 를 마친 지식소스',
     steps='1. 그 지식소스를 동기화한다(POST /api/v1/knowledge-sources/{id}/sync)\n'
           '2. 동기화가 끝난 뒤 query "반품 오류" 와 "배송 지연" 으로 검색한다',
     expect='1. 동기화가 오류 없이 끝난다\n'
            '2. it07-doc-a 와 it07-doc-b 가 그대로 나온다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-052'], src=['CR-170']),

dict(id='IT-07-004', flow='지식소스 검색', kind='정상', title='검색 결과 항목에 넣을 때 준 문서 ID 와 0 ~ 1 벡터 유사도가 있다',
     pre='IT-07-001 을 마친 지식소스',
     steps='1. query "반품 오류" 로 검색한다\n'
           '2. 결과 항목의 칸을 본다',
     expect='1. 항목마다 documentId 가 넣을 때 준 값(it07-doc-a 또는 it07-doc-b)이다\n'
            '2. 항목마다 vectorScore 가 0 이상 1 이하의 수다\n'
            '3. 기존 칸 content, score, sourceId, sourceName, metadata 가 그대로 있다',
     judge=['서버', '서버', '서버'],
     pair={1: [2], 2: [2], 3: [2]},
     req=['PRD-053'], src=['CR-170'], pri='High'),

dict(id='IT-07-005', flow='지식소스 동기화', kind='정상', title='문서가 여럿인 지식소스를 동기화하면 한 번만 비우고 모든 문서를 넣는다',
     pre='platform-core 단위시험(사이드카와 수집은 가짜)',
     steps='1. url 두 개인 지식소스를 fixed 전략으로 동기화한다\n'
           '2. 같은 지식소스를 contextual 전략으로 동기화한다\n'
           '3. 모을 문서가 없는 api 유형 지식소스를 동기화한다',
     expect='1. 첫 문서 전에 지식소스를 한 번 비우고 두 문서를 차례로 넣는다\n'
            '2. 한 번 비우고 두 문서의 청크가 모두 저장된다\n'
            '3. 지우지도 넣지도 않는다',
     judge=['단위', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-052'], src=['CR-170']),

dict(id='IT-07-006', flow='지식소스 문서 넣기', kind='예외', title='문서 ID 없이 넣거나 파일을 넣으면 지식소스 전체를 바꾸고, 파일을 못 읽으면 지우지 않는다',
     pre='사이드카 단위시험(임베딩과 DB 는 가짜)',
     steps='1. document_id 없이 문서를 넣는다\n'
           '2. 파일 지식소스에 파일을 넣는다\n'
           '3. 읽을 수 없는 파일을 넣는다',
     expect='1. 지식소스 전체를 바꾸고 문서 ID 는 지식소스 ID 다\n'
            '2. 지식소스 전체를 바꾼다\n'
            '3. 지우지 않아 기존 임베딩이 남는다',
     judge=['단위', '단위', '단위'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-052'], src=['CR-170']),

]
