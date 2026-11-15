# Aimbase 백로그

해야 하는데 지금 하지 않는 것을 모아 둔다. 상태는 대기, 착수(CR-NNN), 완료, 폐기. 줄을 지우지 않고 상태만 바꾼다.

## BL-001 · 개발기 bp_office 받아쓰기 연결 설정에 키 값이 그대로 저장됨

| | |
|---|---|
| 등록일 | 2026-10-31 |
| 출처 | CR-165 개발기 3단계 준비 중 59 테넌트 DB `aimbase_bp_office` 의 `connections` 를 읽다 발견. 사용자 21:50 승인(컨타 bp-office-59146 경유)으로 등록 |
| 담당(예정) | aimbase |
| 상태 | 대기 |

**무엇**

개발기(59) bp_office 테넌트의 받아쓰기 엔진 연결 `stt-local-mac-001`(type `stt_local`, base_url `http://110.11.139.2:8291`) 설정(`config`)에 맥 받아쓰기 엔진 키 값이 `api_key` 로 그대로 들어 있다. 연결 설정에는 환경변수 이름(`apiKey_env`)만 두고 값은 서버 `.env` 에 두는 규칙에 어긋난다. DB 를 읽을 수 있는 사람이나 백업, 조회 결과에 키가 그대로 드러난다.

확인: 59 호스트에서 `aimbase_bp_office` 에 `select id, config from connections where type = 'stt_local'`.

다른 테넌트, 운영 서버의 받아쓰기 연결도 같은 모양인지는 보지 않았다.

**왜 남겼나**

CR-165(음성 합성) 범위 밖이다. 키를 옮기려면 서버 `.env` 에 변수 추가, api 재생성, 연결 설정 변경이 따라와 별도 CR 로 한다(사용자 21:50). 키가 드러난 상태이므로 옮길 때 맥 받아쓰기 엔진 키를 신규 발급할지도 함께 정한다.

## BL-002 · 검색 재순위가 모두 실패하고 일부 사이드카 도구를 BE 에서 부르면 거절됨

| | |
|---|---|
| 등록일 | 2026-11-08 |
| 출처 | CR-170 로컬 확인과 운영 59 로그(CR-170 문서 8절 1). 컨타 axopm-v5-89936 요청(12:47)으로 등록 |
| 담당(예정) | aimbase |
| 상태 | 대기 |

**무엇**

CR-142 부터 BE 의 `MCPRagClient.callTool` 이 모든 사이드카 도구 호출에 `tenant_db` 를 싣는다. `rerank_results`, `chunk_document`, `contextual_chunk`, `scrape_url`, `parse_document`, `transform_query`, `compress_context` 등은 이 인자를 받지 않아 사이드카 입력 검사에서 거절된다.

- ① 검색: 재순위가 매번 실패하고 BE 는 재순위 없이 하이브리드 결합 점수를 돌려준다. 59 에서 11-01 ~ 11-08 사이드카 검색 19건, CR-170 반영 뒤 3건이 모두 "Reranking failed" 였다. 검색 응답의 `score` 는 재순위 점수가 아니라 결합 점수(0.016 안팎)다
- ② 동기화: 사이드카로 웹 페이지를 모으는 url 유형, contextual 전략 등 위 도구를 BE 가 부르는 경로도 같은 이유로 실패한다

재현: 로컬이나 59 에서 `POST /api/v1/knowledge-sources/search` 를 부르면 api 로그에 "Reranking failed" 가 남는다.

**왜 남겼나**

CR-170(문서 넣기 범위, 검색 결과 칸) 범위 밖이다. `documentId`, `vectorScore` 는 재순위와 상관없이 나와 CR-170 과 AXOPM CR-168 은 막히지 않는다. 재순위가 붙으면 `score` 의 크기가 바뀌므로 고칠 때 소비앱(AXOPM)에 알린다.

## BL-003 · 사이드카 라이브러리 판이 고정되지 않아 배포 때마다 최신 판을 받음

| | |
|---|---|
| 등록일 | 2026-11-08 |
| 출처 | CR-170 운영 59 사이드카 반영(CR-170 문서 8절 3, 6.3). 컨타 axopm-v5-89936 요청(12:47)으로 등록 |
| 담당(예정) | aimbase |
| 상태 | 대기 |

**무엇**

`python-sidecar/pyproject.toml` 은 하한만 두고 잠금 파일이 없다. `python-sidecar/Dockerfile` 은 소스를 복사한 뒤 라이브러리를 설치해, 소스가 한 줄만 바뀌어도 `./deploy.sh rag` 가 그날의 최신 판을 다시 받는다(빌드 약 200초).

11-08 CR-170 반영에서 304개 중 190개의 판이 바뀌었다. fastmcp 3.4.7 에서 4.1.6, mcp 1.29.0 에서 2.3.0, torch 2.13.0 에서 2.15.0, transformers 5.15.1 에서 5.22.2, starlette 1.6.0 에서 1.10.0 등. 이번에는 검색 영향이 없었다(옛 판으로 저장한 청크를 새 판으로 검색하면 자기 청크 유사도 1.0). 로컬 개발 환경(fastmcp 3.1.1, mcp 1.26.0)과 운영 판도 서로 다르다.

**왜 남겼나**

CR-170 범위 밖이다. 다음 사이드카 반영에서 큰 판 변경이 들어오면 로컬에서 본 것과 다른 판으로 운영이 돌고, 깨져도 배포 뒤에야 안다. 판을 고정하려면 잠금 파일과 이미지 설치 순서를 바꾸고, 지금 운영 판을 기준으로 할지 정해야 해 별건으로 한다.

## BL-004 · 워크플로우 저장 검증이 막을 때 사유가 화면과 응답에 나오지 않는다

| | |
|---|---|
| 등록일 | 2026-11-15 |
| 출처 | CR-193 로컬 확인(IT-23-006, IT-23-007). CR_변경_이력 「CR 후보 2」(CR-149 발견)와 같은 건. 컨타 lexflow-88782 요청(20:43)으로 등록 |
| 담당(예정) | aimbase |
| 상태 | 대기 |

**무엇**

① 서버: `WorkflowValidator` 는 `ResponseStatusException`(400)으로 사유를 던지는데(`WorkflowValidator.java:390`), `GlobalExceptionHandler` 가 이 예외를 처리하지 않고 `server.error.include-message` 설정도 없어 응답 본문이 Spring 기본 틀(`timestamp, status, error, path`)뿐이다. 재현: `POST /api/v1/workflows` 에 검사 반복 `code_check.skill` 을 빈 글로 보내면 400 인데 본문에 `code_check.skill is required` 가 없다. 사유 문구는 단위시험 TC-ECK-014 로만 보인다.

② 화면: 워크플로우 편집 화면 저장이 실패하면 서버 응답과 상관없이 「저장에 실패했습니다.」 알림만 띄운다(`WorkflowStudio.tsx:279`). CR-193 확인 중 편집기가 최대 반복 횟수를 글로 보내 400 이 났을 때도 이 문구만 보여 원인을 요청 본문을 재전송해 가며 찾았다.

**왜 남겼나**

CR-193 범위 밖이다. 예외 처리 틀을 바꾸면 워크플로우 말고도 `ResponseStatusException` 을 쓰는 모든 API 의 오류 응답 모양이 바뀌어 소비앱 영향을 따로 봐야 한다. 편집 화면에서 설정을 잘못 넣는 사람이 원인을 알 수 없으므로 다음 워크플로우 편집 관련 CR 전에는 한다.
