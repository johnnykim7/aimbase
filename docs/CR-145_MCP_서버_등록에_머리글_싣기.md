# CR-145: MCP 서버 등록에 머리글 싣기

- 발번: 2026-10-11
- 원본 요구사항: `docs/origins/원본_요구사항_CR145_MCP_서버_등록에_머리글_싣기_20261011.md`
- 요청: 입찰대행 플랫폼 CR-144 정할 것 1 ①
- 관련 규칙: BIZ-117 (신규)
- 규모: 소. 새 API, 화면, 테이블이 없다. 기존 등록 config 에 선택 칸 하나가 늘고, 연결 코드와 등록 조회 응답이 바뀐다
- 상태: 구현 완료, 2026-10-11 운영 배포

---

## 1. 문제

Aimbase 가 바깥 MCP 서버를 부를 때 등록의 주소만 쓰고 머리글을 싣지 못한다.
입찰대행 플랫폼이 자기 MCP 통로에 X-API-Key 확인을 넣으면 Aimbase 는 열쇠를 실을 방법이 없어 연결이 끊긴다.
주소 물음표 뒤에 열쇠를 붙여도 연결할 때 버려진다.

## 2. 실측 근거

| 항목 | 위치 |
|---|---|
| 등록 config 에서 url 하나만 읽는다 | `MCPServerClient` 생성자 (mcp/MCPServerClient.java:71) |
| SSE, Streamable 연결을 만들 때 머리글을 싣지 않는다 | `MCPServerClient.buildClient` (mcp/MCPServerClient.java:101~112) |
| 주소의 물음표 뒤 값을 버린다 | `splitBaseAndSsePath` (mcp/MCPServerClient.java:156~182) |
| SDK 두 연결 모두 요청 꾸밈 기능(`httpRequestCustomizer`)이 있다. SSE 는 연결(GET)과 메시지 보내기(POST), Streamable 은 POST, GET, DELETE 요청마다 부른다 | mcp-core 2.0.0 `HttpClientSseClientTransport`, `HttpClientStreamableHttpTransport` |
| 등록 목록, 상세, 등록, 수정 응답이 config 를 그대로 내보낸다 | `MCPController` list, create, get, update (api/MCPController.java:34~77) |
| 수정은 config 를 통째로 바꾼다 | `MCPController.update` (api/MCPController.java:74) |
| 등록을 연결로 바꾸는 곳은 한 군데다 | `MCPServerManager.createClient` (mcp/MCPServerManager.java:234) |
| 사이드카(RAG, 안전, 평가)와 원격 에이전트 연결은 코드에서 url 만 넘긴다 | MCPRagClient.java:67, MCPSafetyClient.java:49, MCPEvaluationClient.java:49, RemoteAgentToolExecutor.java:42 |
| 관리 화면 등록 창은 url 만 보내고 수정 창이 없다 | frontend/src/pages/MCPServers.tsx:39~49 |

## 3. 설계

### 3.1 등록 config 모양

```json
{
  "url": "http://host:8183/api/mcp",
  "headers": { "X-API-Key": "<값>" }
}
```

1. `headers` 는 선택이다. 없거나 비어 있으면 지금과 같다
2. 이름과 값은 모두 문자열이다. 연결 방식(sse, http, streamable)과 무관하다
3. 아래면 등록과 수정을 400 으로 거부한다
   - `headers` 가 객체가 아니다
   - 값이 문자열이 아니거나 비어 있다
   - Java HTTP 클라이언트가 받지 않는 이름이다(Host, Content-Length, Connection 같은 제한 머리글, 허용되지 않는 문자). 판정은 클라이언트에 그대로 맡겨 목록을 따로 두지 않는다

### 3.2 연결

1. 등록의 머리글을 SDK 요청 꾸밈 기능으로 모든 요청에 싣는다. SSE 는 연결과 메시지 보내기, Streamable 은 모든 요청이다
2. 세션이 끊겨 다시 붙을 때(호출 중 재연결, 헬스체크 재연결)도 같은 머리글을 싣는다
3. SDK 가 정한 머리글과 이름이 같으면 등록 값으로 바꾼다
4. 수정(PUT)은 저장만 한다. 새 머리글은 재연결(`POST /mcp-servers/{id}/reconnect`)이나 다음 기동 때부터 실린다. 지금 수정 규칙과 같다

### 3.3 값 가리기 (BIZ-117)

1. 등록 목록, 상세, 등록 응답, 수정 응답에서 `headers` 의 값은 `****` 로 내보낸다. 이름은 보인다. 응답의 다른 칸 모양은 그대로다
2. 수정 요청의 머리글 값이 `****` 이면 저장된 값을 유지한다. 조회한 config 를 그대로 되돌려 보내도 열쇠가 지워지지 않게 하기 위해서다. 새 값이면 바꾸고, 요청에 없는 이름은 지운다(수정은 config 를 통째로 바꾸는 지금 규칙을 따른다)
3. 저장된 것이 없는 이름에 `****` 를 보내면 400 이다
4. 로그에는 머리글 이름만 남기고 값은 남기지 않는다
5. 가리는 것은 응답용 사본이다. 저장된 등록은 건드리지 않는다

## 4. 범위 밖

- 첨부 내려받기(DownloadFileTool). 입찰대행이 서명 주소로 푼다
- 주소 물음표 뒤 값을 버리는 동작. 열쇠는 머리글로 싣게 되어 이번에 바꾸지 않는다
- 머리글 값의 저장 암호화. config 는 지금처럼 테넌트 DB 에 그대로 저장된다
- 관리 화면 등록 창의 머리글 입력. 등록은 API 로 한다
- Aimbase 운영 서버에서 입찰대행 8183 에 닿지 못하는 망 문제. 입찰대행 쪽 새 CR 이 다룬다

## 5. 영향

모든 테넌트의 MCP 서버 등록이 대상이다. `headers` 가 없는 등록은 연결 요청이 바뀌지 않는다.
조회 응답은 `headers` 가 있는 등록만 값이 가려지고 나머지는 같다.
DB 스키마 변경은 없다. 사이드카와 원격 에이전트 연결은 그대로다.

## 6. 시험

T3-5 TC-MCPH-001 ~ TC-MCPH-007.

## 7. 확인

### 7.1 단위시험 (2026-10-11)

- MCPServerHeadersTest 8건, MCPControllerHeadersTest 6건, MCPServerClientHeadersTest 4건(가짜 MCP 서버로 SSE, Streamable 실제 연결) 통과
- 기존 MCPServerClientUrlSplitTest, Cr124StreamableUrlSplitTest, MCPServerManagerTest, MCPToolExecutorLazyReconnectTest 통과

### 7.2 로컬 (2026-10-11, BE 8181, tenant_dev, 열쇠를 요구하는 가짜 MCP SSE 서버)

| 순번 | 요청 | 결과 |
|------|------|------|
| 1 | 머리글 있는 등록, 등록 응답·상세·목록 | 값 `****`, 이름 X-API-Key 보임 |
| 2 | 그 등록으로 도구 탐색 | 도구 1개. 상대 서버가 받은 연결 GET 2회, 메시지 POST 6회 모두 맞는 열쇠 |
| 3 | 머리글 없는 등록으로 도구 탐색 | 도구 1개. 상대 서버에 머리글 없음(기존 동작) |
| 4 | 열쇠를 요구하는 서버를 머리글 없이 등록, 도구 탐색 | 502 |
| 5 | 수정에 `****` 그대로 보내고 재연결 | 연결됨. DB 저장값은 원래 열쇠 그대로 |
| 6 | 수정에 Host 머리글 | 400 |
| 7 | 앱 로그 | 열쇠 값 0건, 연결 로그에 headers=[X-API-Key] |

### 7.3 운영 (2026-10-11 16:03 BE 배포)

- 배포 전 운영 jar 958개 파일을 작업 폴더 빌드와 대조해 다른 것이 CR-145 클래스뿐임을 확인했다
- 배포 뒤 자동 연결: axopm_companyA rag-pipeline 연결, 도구 42개 등록(배포 전과 같음). bidding_system 등록은 배포 전과 같이 연결 실패(입찰대행 8183 망 문제, 범위 밖)
- 러너 하트비트 계속 들어옴, 기동 뒤 ERROR 로그 0건
- 입찰대행 등록에 headers 를 넣는 것은 입찰대행 CR-144 가 열쇠를 켤 때 한다
