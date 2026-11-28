# CR-206 지식 자료 ID 토큰 결합, 사무소별 격리(LexFlow Q10)

> 설계 버전: 1.1 | 최종 수정: 2026-11-27 | 관련 CR: CR-206, CR-058, CR-194, CR-190

- 원본 요구사항: [origins/원본_요구사항_CR206_지식_자료_ID_토큰_결합_20261127.md](origins/원본_요구사항_CR206_지식_자료_ID_토큰_결합_20261127.md)
- 출처: LexFlow 검토 회신 2026-11-25 질문 Q10 「공통 자료(RAG)를 사무소별로 분리해 등록할 수 있는지요?」. AI 파트 답변 초안 4장 추가 개발 6번
- 기능 ID: PRD-648, PRD-649. PRD-001, PRD-002 의 입력 `rag_source_id` 보정
- 규칙: BIZ-290, BIZ-291
- 규모: 중. 소비앱이 부르는 토큰 발급 API 에 칸이 늘고, 토큰 내용과 대화 요청의 검사 규칙이 새로 생기며, 지식 자료 API 의 위젯 토큰 접근 범위가 바뀐다. 화면과 표는 바뀌지 않는다

## 1. 범위

### 1.1 이번 CR 에 넣는 것

| 순번 | 기능 | 기능 ID | 바뀌는 곳 |
|---|---|---|---|
| 1 | 대화 요청의 자료 ID 를 서버가 받는다: 위젯이 보내는 `rag_source_id` 를 대화 요청 칸에 두고 자료 검색으로 넘긴다. 지금은 서버가 이 값을 버린다(2절 1번) | PRD-001, PRD-002 보정 | 서버 |
| 2 | 토큰에 허용 자료 싣기: 붙이는 앱의 서버가 위젯 토큰을 받을 때 그 사용자가 쓸 수 있는 지식 자료 ID 목록(`rag_source_ids`)을 싣는다. 모두 이 테넌트의 자료여야 한다. 첫 자료가 기본 자료다 | PRD-648 | 서버 |
| 3 | 대화 요청과 청크 원문 조회의 자료 대조: 허용 자료가 있는 토큰은 그 목록 안의 자료만 쓴다. 대화 요청에 자료 ID 가 없으면 기본 자료다. 목록 밖이거나 테넌트에 없는 자료는 없는 것으로 본다 | PRD-648 | 서버 |
| 4 | 지식 자료 API 의 위젯 토큰 제한: 위젯 토큰은 청크 원문 조회만 부른다. 목록, 상세, 검색 같은 나머지 창구는 거절한다 | PRD-649 | 서버 |
| 5 | 소비앱 연동 가이드(API 가이드, 위젯 붙이기 가이드, 운용 가이드) 반영 | | 문서 |

### 1.2 넣지 않는 것

| 순번 | 무엇 | 이유 |
|---|---|---|
| 1 | 위젯 화면과 속성 변경 | 속성 `rag-source-id` 는 그대로다. 토큰에 허용 자료가 있으면 속성을 생략해도 기본 자료로 간다. 바꿀 화면이 없다 |
| 2 | 한 질문에 여러 자료를 함께 검색 | 요구에 없다. 한 질문은 자료 하나다. 공통 자료와 사무소 자료를 같이 쓰려면 토큰 목록에 둘을 싣고 화면이 질문마다 하나를 고른다 |
| 3 | 지식 자료 외 다른 관리 API(연결, 정책, 워크플로우 등)의 위젯 토큰 제한 | 이 CR 은 자료 격리 범위만 다룬다. 위젯 토큰이 `/api/v1/**` 를 로그인과 같은 인증으로 부르는 것(2절 3번)은 후속 CR 후보로 남긴다 |
| 4 | 로그인(관리 화면) 사용자의 자료 접근 변경 | 관리 화면은 테넌트의 자료를 모두 다루는 곳이다 |
| 5 | 자료 등록을 사무소별로 나누는 관리 화면 | 지식 자료는 지금처럼 테넌트 관리자가 등록한다. 사무소 구분은 토큰에 싣는 ID 목록으로 한다 |

### 1.3 규칙

| 규칙 | 내용 |
|---|---|
| BIZ-290 | 위젯 토큰에 허용 자료 목록이 있으면 대화와 청크 원문 조회는 그 목록 안의 자료만 쓴다. 발급 요청 `rag_source_ids` 의 자료는 모두 그 테넌트의 지식 자료여야 하고(아니면 400 `widget_token_invalid:rag_source_id`), 첫 자료가 기본 자료다. 대화 요청에 자료 ID 가 없으면 기본 자료, 목록 밖이거나 테넌트에 없는 자료는 없는 것으로 보고 404 `rag_source_not_found`. 목록이 없는 위젯 토큰과 로그인은 지금처럼 테넌트의 어느 자료든 쓴다. 자료 ID 는 100자 이하, 목록은 20개 이하, 빈 값과 중복은 뺀다 |
| BIZ-291 | 위젯 토큰은 지식 자료 API 중 청크 원문 조회만 부른다. 그 밖의 `/api/v1/knowledge-sources/**` 호출은 403 `widget_forbidden`. 관리 화면의 로그인 호출은 바뀌지 않는다 |

## 2. 지금 상태 (설계 근거, 2026-11-27 origin/dev b83dcb32)

| 순번 | 무엇 | 근거 |
|---|---|---|
| 1 | 위젯은 속성 `rag-source-id` 를 대화 요청 본문 `rag_source_id` 로 보내지만, 서버의 대화 요청 칸에 그 이름이 없다. 모르는 칸이라 버려지고 자료 검색은 한 번도 돌지 않았다. 검색 자체는 요청에 자료 ID 가 있으면 돈다. API 가이드 § 17-4 와 T1-1 PRD-001 은 받는다고 적혀 있다 | 위젯 `chat-client.ts:101`, 서버 `ChatController.java:487`(요청 칸), `:220`(검색 자료 자리에 null), `OrchestratorEngine.java:301`(검색). `git log -S rag_source_id` 에 api 패키지 이력 없음 |
| 2 | 위젯 토큰에는 자료가 실리지 않는다. 실리는 것은 tenant_id, project_id, user_ref, user_name, share_key, scopes, origin, type 이다 | `JwtProvider.java:71`, `JwtAuthenticationFilter.java:171` |
| 3 | 위젯 토큰은 로그인과 같은 인증 객체(역할 WIDGET)로 `/api/v1/**` 를 부른다. 관리용 대화 API 만 CR-194 가 위젯을 거절한다. 지식 자료 API 는 목록, 상세, 검색, 등록까지 위젯 토큰에 열려 있고, 청크 원문 조회는 테넌트 안 어느 자료든 읽는다 | `SecurityConfig.java:102`, `ConversationController.java:44`, `KnowledgeController.java:73`, `:280`, `:382` |
| 4 | 지식 자료 표(knowledge_sources)는 테넌트 DB 에 있다. 자료 ID 로 찾으면 그 테넌트 안에서만 찾는다 | `KnowledgeSourceEntity.java:11`, BIZ-003 |
| 5 | 토큰 발급 칸은 CR-194 방식이 있다. 모르는 칸은 무시하고, 값 검사 실패는 400 `widget_token_invalid:<칸>` 이다 | `WidgetTokenController.java:93`, `ChatKeys.java` |

## 3. 서버

### 3.1 위젯 토큰 발급 칸 [PRD-648]

`POST /api/v1/sessions/issue-widget-token` 요청에 칸 하나를 더한다. 모르는 칸은 지금처럼 무시한다. 응답은 지금과 같다.

| 칸 | 타입 | 필수 | 뜻 |
|---|---|---|---|
| rag_source_ids | string[] | 선택 | 허용 자료 ID 목록. 첫 자료가 기본 자료. 각 100자 이하, 20개 이하. 빈 값과 중복은 빼고 싣는다 |

- 각 ID 가 이 테넌트의 지식 자료여야 한다. 하나라도 없으면 토큰을 내지 않는다
- 토큰 claim `rag_source_ids` 에 정리한 목록을 싣는다. 목록이 비면 claim 을 두지 않는다(허용 자료 없는 토큰)

| 오류 | 경우 |
|---|---|
| 400 `widget_token_invalid:rag_source_id` | 테넌트에 없는 자료, 100자 초과, 20개 초과 |

### 3.2 대화 요청 [PRD-001, PRD-002, PRD-648, BIZ-290]

`POST /api/v1/chat/completions` 에 `rag_source_id`(string, 선택) 칸을 둔다. 정해진 자료 ID 는 자료 검색(OrchestratorEngine 3-1, CR-058 출처 카드)으로 간다.

대조는 대화 접근 확인(BIZ-275) 다음, 모델 검사 전에 한다. 스트림을 열기 전이라 거절을 HTTP 코드로 준다.

| 요청자 | 요청의 rag_source_id | 결과 |
|---|---|---|
| 허용 자료 있는 위젯 토큰 | 없음 | 기본 자료(목록 첫 자료)로 검색 |
| 허용 자료 있는 위젯 토큰 | 목록 안 | 그 자료로 검색 |
| 허용 자료 있는 위젯 토큰 | 목록 밖 | 404 `rag_source_not_found` |
| 허용 자료 없는 위젯 토큰, 로그인 | 없음 | 검색 없음(지금과 같다) |
| 허용 자료 없는 위젯 토큰, 로그인 | 테넌트에 있는 자료 | 그 자료로 검색 |
| 누구든 | 테넌트에 없는 자료 | 404 `rag_source_not_found` |

- 목록 밖과 없는 자료를 같은 코드로 거절한다. 다른 사무소 자료가 있다는 것을 알리지 않기 위해서다(CR-194 BIZ-275 의 404 `session_not_found` 와 같은 생각)
- 그림 질문(`image`)은 자료를 쓰지 않지만 대조는 같이 거친다. 요청 하나에 규칙 하나다

### 3.3 청크 원문 조회 [PRD-648, BIZ-290]

`GET /api/v1/knowledge-sources/{sourceId}/chunks/{chunkId}`. 허용 자료 있는 위젯 토큰은 `sourceId` 가 목록 안이어야 한다. 아니면 404 `rag_source_not_found`. 허용 자료 없는 위젯 토큰과 로그인은 지금과 같다.

### 3.4 지식 자료 API 위젯 토큰 제한 [PRD-649, BIZ-291]

`/api/v1/knowledge-sources/**` 중 청크 원문 조회를 뺀 모든 창구(목록, 상세, 검색, 등록, 수정, 삭제, 파일 올리기, 동기화, 글 넣기, 문서 삭제, 기록 조회)는 위젯 토큰이면 403 `widget_forbidden` 이다. CR-194 가 관리용 대화 API 에 둔 것과 같은 코드다. 구현은 그 경로에 붙는 요청 가로채기(HandlerInterceptor) 하나로 하고, 청크 원문 조회 경로만 뺀다.

### 3.5 공통 부품

- `ChatOwner` 에 `RAG_SOURCE_IDS_KEY` 와 `ragSourceIdsOf(principal)` 을 둔다. `JwtAuthenticationFilter.authenticateWidget` 이 claim 을 위젯 정보에 담는다. user_ref 가 없는 토큰에도 담는다(자료 격리는 사람이 아니라 사무소 단위다)
- `RagSourceAccess`(session 패키지, `ChatSessionOwnership` 과 같은 자리): `resolve(requested)` 가 3.2 표대로 자료 ID 를 정하거나 404 를 던지고, `requireReadable(sourceId)` 가 3.3 을 한다. 발급 때 목록 정리와 존재 확인은 `forToken(list)` 이다. 존재는 `KnowledgeSourceRepository.existsById` 로 본다(knowledge_sources 는 테넌트 DB 표)
- 거절은 `ChatRejectedException` 으로 던진다. 전역 처리기가 `ApiResponse.error(code)` 로 돌려준다

## 4. 위젯

바뀌지 않는다. 속성 `rag-source-id` 는 지금처럼 요청에 실린다. 토큰에 허용 자료가 있으면 속성을 비워도 기본 자료로 검색되고, 속성이 목록 밖이면 404 로 거절된다. 위젯은 지금의 오류 처리로 코드를 보인다.

## 5. 데이터

표와 마이그레이션 변경 없음. 토큰 claim 하나가 는다.

## 6. 소비앱(LexFlow) 연동 안내에 반영할 것

| 순번 | 무엇 | 지금 안내서 | 바뀐 뒤 |
|---|---|---|---|
| 1 | 사무소별 자료 | 채팅창 속성 `rag-source-id` 에 자료 ID 를 지정(개발자 안내 3-2, 속성 표 9번) | LexFlow 서버(BFF)가 토큰 발급 요청에 그 사용자 사무소의 자료 ID 를 `rag_source_ids` 로 싣는다. 속성은 생략 가능. 브라우저가 값을 바꿔도 목록 밖은 404 |
| 2 | 토큰 발급 API 규격 | API 가이드 § 17-2 | § 17-2 와 § 39(CR-206) |
| 3 | 오류 | 없음 | 404 `rag_source_not_found`, 400 `widget_token_invalid:rag_source_id` |
| 4 | 답변 초안 Q10 | 「추가 개발」 | 토큰에 자료 ID 를 결합하는 기능이 들어갔다. 사무소마다 자료를 등록해 ID 를 회신하고, LexFlow 가 사무소별로 토큰에 싣는다 |

## 7. 레포 간 요청

| 순번 | 레포 | 요청 |
|---|---|---|
| 1 | lexflow(문서) | 6절 표대로 안내서 둘과 답변 초안을 고친다. 완료 시 컨타 lexflow-7434 에 `[알림]` 한 줄과 이 문서 경로를 보낸다 |

## 8. 시험

### 8.1 단위시험 (T3-5)

| TC | 대상 | 내용 |
|---|---|---|
| TC-CHT-159 | 토큰 발급 칸 | rag_source_ids 가 토큰에 실린다. 빈 값과 중복은 빠진다. 없는 자료, 101자, 21개는 400. 칸이 없으면 claim 도 없다 |
| TC-CHT-160 | 대화 요청 대조 | 3.2 표의 여섯 경우. 정해진 자료 ID 가 ChatRequest.ragSourceId 로 넘어간다. 거절은 응답 시작 전이다 |
| TC-CHT-161 | 지식 자료 API | 청크 원문 조회의 목록 안과 밖. 위젯 토큰의 목록, 상세, 검색은 403. 로그인은 200 |

### 8.2 통합 시험항목

- 영역 28 「위젯 지식 자료 토큰 결합」 IT-28-001 부터 IT-28-004(`tools/testbook/items/items_28.py`). 서버 판정 4건

## 9. 구현 순서

| 순번 | 무엇 |
|---|---|
| 1 | 대화 요청 칸 `rag_source_id` 와 검색 전달(1.1 의 1번) |
| 2 | 토큰 발급 칸, claim, 필터, `ChatOwner` 칸 |
| 3 | `RagSourceAccess`, 대화 요청 대조, 청크 원문 조회 대조 |
| 4 | 지식 자료 API 위젯 토큰 가로채기 |
| 5 | 단위시험, 가이드 셋 |
| 6 | 로컬 확인(bootRun, 시험 테넌트에 자료 둘, 허용 목록 토큰과 목록 없는 토큰으로 3.2 표 재현) |
| 7 | 59 와 운영 배포는 사용자 승인 뒤 컨타 신호. 배포 뒤 컨타에 LexFlow 문서 반영 사항 알림 |

## 10. 확인 기록

| 일시 | 무엇 | 결과 |
|---|---|---|
| 2026-11-27 23:05 | origin/dev b83dcb32 조사. 대화 요청이 `rag_source_id` 를 받지 않는 것, 위젯 토큰이 지식 자료 API 전체에 열린 것을 확인 | 2절. 범위에 1.1 의 1번과 4번을 더함 |
| 2026-11-27 23:15 | 단위시험. TC-CHT-159~161 을 `RagSourceAccessTest`, `KnowledgeWidgetGuardTest`(새 파일), `WidgetTokenControllerTest`, `JwtProviderWidgetTest`, `JwtAuthenticationFilterWidgetTest`, `ChatOwnerTest`, `ChatWidgetAccessTest`, `KnowledgeControllerChunkTest` 에 더함. 대화 요청 칸이 늘어 `ChatController` 시험 5개의 주입 목록도 고침 | 13개 클래스 75건 통과 |
| 2026-11-27 23:22 | 로컬 확인. 워크트리 bootRun(local, 18206, Redis 도커 16206), 맥 PG 테넌트 `cr196_local`(V96)에 자료 `kb_office_a`, `kb_office_b` 두 행과 master `api_keys` 에 발급용 키를 넣고 curl 로 9경로 | 아래 표. 전부 3절대로 |
| 2026-11-27 23:25 | 로컬 서버와 Redis 내림. 시험 행(자료 두 개, 키 한 개)은 맥 PG 에 남김 | 배포 대기 |
| 2026-11-27 23:31 | 59 반영(사용자 23:30 승인, 컨타 전달). 가지 `cr206-rag-token-bind` 를 origin/dev 에 빨리감기로 올리고(fd977ac5) 그 워크트리에서 `./deploy.sh be` | api 재기동, 상태 UP. 마이그레이션 없음 |
| 2026-11-28 07:33 | 59 확인. 테넌트 lexflow 의 붙이는 앱 키로 토큰을 받아 읽기와 거절 경로만 침(대화 저장, 모델 호출, DB 쓰기 없음) | 아래 표. lexflow 에는 59 에 지식 자료가 없어 허용 목록 안 자료 경로는 59 에서 치지 못함(로컬 5, 6, 8번으로 확인) |
| 2026-11-28 15:00 | 59 허용 목록 안 자료 확인(사용자 14:56 승인, 컨타 전달). lexflow 에 시험 자료 `kb_cr206_check` 를 관리자 API 로 만들고 글 한 건을 넣어 대화와 청크 원문을 침. 청크 번호는 59 DB 읽기로 가져옴 | 아래 두 번째 표. 대화 두 건과 시험 자료를 지움(자료 조회 404, 청크 0행, 자료 0행) |

59 확인 결과(2026-11-28 07:33, aimbase.banpoom.co.kr, 판 fd977ac5):

| 순번 | 요청 | 결과 |
|---|---|---|
| 1 | 발급 `rag_source_ids: ["kb_cr206_nope"]` | 400 `widget_token_invalid:rag_source_id` |
| 2 | 발급, 칸 없음 | 200, claim 없음 |
| 3 | 목록 없는 토큰, 대화 `rag_source_id: kb_cr206_nope` | 404 `rag_source_not_found` |
| 4 | 위젯 토큰, 지식 자료 목록 / 검색 | 403 `widget_forbidden` |
| 5 | 위젯 토큰, 청크 원문 `kb_cr206_nope/chunks/<uuid>` | 가로채지 않음. 404 `Not Found`(자료 없음) |
| 6 | 관리자 로그인, 지식 자료 목록 | 200 |

59 허용 목록 안 자료 확인(2026-11-28 15:00, 재기동 뒤 판, origin/dev a19054d3 에 CR-206 포함):

| 순번 | 요청 | 결과 |
|---|---|---|
| 1 | 발급 `rag_source_ids: ["kb_cr206_check"]` | 200, claim `["kb_cr206_check"]` |
| 2 | 허용 토큰, 대화에 자료 없음 | 200. 기본 자료로 대조 통과, 같은 요청 안에서 검색이 돎(api 기록) |
| 3 | 허용 토큰, 대화 `rag_source_id: kb_cr206_check` | 200. 대조 통과, 검색이 돎(api 기록) |
| 4 | 허용 토큰, 청크 `kb_cr206_check/chunks/<uuid>` | 200, 원문 반환 |
| 5 | 목록 없는 토큰, 같은 청크 | 200(제한 없음) |

2, 3번의 답에는 자료 내용과 출처가 붙지 않았다. 이 CR 범위 밖의 결함이다. lexflow 검색 설정 `default` 의 유사도 문턱이 0.7 인데, 59 의 재정렬 호출이 실패해(`rerank_results` 입력 검증 오류) 점수가 하이브리드 합산 점수(0.016 안팎)로 남고, 그 점수를 0.7 문턱과 견줘 검색된 청크가 모두 빠진다(`RAGService.buildContextWithCitations`, `VectorSearcher` 점수 선택). 사무소별 자료로 답하는 LexFlow Q10 의 최종 동작은 이 결함을 고쳐야 나온다. 별도 CR 로 컨타에 올림.

로컬 확인 결과(2026-11-27 23:22, 18206):

| 순번 | 요청 | 결과 |
|---|---|---|
| 1 | 발급 `rag_source_ids: ["kb_office_a", " ", "kb_office_b", "kb_office_a"]` | 200, claim `["kb_office_a", "kb_office_b"]` |
| 2 | 발급 `["kb_office_a", "kb_other"]` | 400 `widget_token_invalid:rag_source_id` |
| 3 | 발급, 칸 없음 | 200, claim 없음 |
| 4 | 허용 토큰, 대화 `rag_source_id: kb_other` | 404 `rag_source_not_found` |
| 5 | 허용 토큰, 대화 `kb_office_b` | 대조 통과, 검색 단계 진입. 503 `EmbeddingModel not available`(로컬에 임베딩 모델이 없어서. 59 와 운영은 있음) |
| 6 | 허용 토큰, 대화에 자료 없음 | 기본 자료로 대조 통과, 검색 단계 진입(5 와 같은 503) |
| 7 | 목록 없는 토큰, 대화 `kb_nope` / `kb_office_b` | 404 `rag_source_not_found` / 대조 통과(5 와 같은 503) |
| 8 | 허용 토큰, 청크 `kb_office_b/chunks/<uuid>` / `kb_other/chunks/<uuid>` | 404 `Not Found`(자료는 통과, 청크가 없음) / 404 `rag_source_not_found` |
| 9 | 위젯 토큰, 지식 자료 목록 / 상세 / 검색 | 모두 403 `widget_forbidden` |
