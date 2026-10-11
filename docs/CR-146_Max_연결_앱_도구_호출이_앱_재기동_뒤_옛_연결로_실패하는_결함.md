# CR-146: Max 연결 앱 도구 호출이 앱 재기동 뒤 옛 연결로 실패하는 결함

- 발번: 2026-10-12
- 원본 요구사항: `docs/origins/원본_요구사항_CR146_Max_연결_앱_도구_호출_재기동_뒤_실패_20261012.md`
- 요청: 입찰대행 플랫폼 CR-150 창
- 규모: 소. 새 API, 화면, 테이블이 없다. /mcp 도구 호출과 바깥 MCP 호출 중 재연결의 결함 수정
- 상태: 진행중

---

## 1. 문제

CLI 연결(tool_mode AIMBASE)이 Aimbase /mcp 로 앱 도구(입찰대행 MCP 서버의 get_opportunity 등)를 부르면, 앱 재기동 뒤 바깥 MCP 연결이 한 번 다시 붙은 다음부터 "Client failed to initialize by explicit API call" 로 실패한다.
REST 재연결과 헬스체크 재연결로는 풀리지 않고 Aimbase 를 재기동해야 풀린다.
같은 도구를 워크플로우 TOOL_CALL 과 API 연결 채팅으로 부르면 문제없다.

## 2. 원인

### 2.1 /mcp 가 처음 실을 때의 실행기를 계속 쓴다

| 항목 | 위치 |
|---|---|
| 도구를 처음 실을 때 핸들러가 그 순간의 실행기를 잡아 두고, 이름이 이미 실려 있으면 다시 싣지 않는다 | `ServerMcpConfig.syncOnce` (mcp/server/ServerMcpConfig.java:172~186) |
| 재연결은 옛 연결을 닫고 새 연결로 만든 실행기를 같은 이름으로 도구 목록에 덮어쓴다 | `MCPServerManager.reconnect`, `discover` (mcp/MCPServerManager.java:137~153, 121~123) |
| 닫힌 연결은 다음 호출 때 초기화를 다시 시도하고 SDK 가 이를 거절한다. 이것이 증상의 오류 문구다 | `MCPServerClient.close`, `ensureConnected` (mcp/MCPServerClient.java:408~419, 264~268) |
| 워크플로우와 API 채팅은 호출 때마다 도구 목록에서 이름으로 실행기를 찾으므로 새 연결을 쓴다 | `ToolRegistry.execute` (tool/ToolRegistry.java:225~230) |

재연결이 한 번 일어나면 /mcp 만 닫힌 옛 연결에 묶인다. Aimbase 재기동 때만 도구를 새로 싣기 때문에 재기동해야 풀린다.

### 2.2 /mcp 호출 중 재연결이 테넌트를 잃는다

| 항목 | 위치 |
|---|---|
| SDK 는 동기 도구 핸들러를 요청 스레드가 아닌 boundedElastic 스레드에서 돌린다(immediateExecution 기본 false) | mcp-core 2.0.0 `McpServer$SyncSpecification`, `McpServerFeatures$AsyncToolSpecification` |
| 테넌트는 요청 스레드의 ThreadLocal 에만 있다 | `TenantContext` (tenant/TenantContext.java:10) |
| 실행기의 호출 중 재연결은 부른 쪽의 테넌트로 `MCPServerManager.reconnect` 를 부른다 | `MCPToolExecutor.execute` (mcp/MCPToolExecutor.java:55~66) |
| `reconnect` 는 연결을 먼저 지우고 닫은 뒤 등록을 조회한다. 테넌트가 없으면 조회가 master DB 로 가서 실패한다 | mcp/MCPServerManager.java:137~153 |

/mcp 경로에서 이 재연결이 돌면 연결만 닫히고 다시 붙지 못한다. 이때 닫히는 것은 워크플로우와 API 채팅이 쓰는 지금 연결이다(로컬 재현 로그, 6.2). 2.1 을 고쳐도 앱이 내려가 있는 동안 /mcp 호출이 한 번 들어오면, 앱이 다시 떠도 헬스체크(60초 주기, 테넌트 지정)가 다시 붙일 때까지 /mcp 호출이 실패한다.

## 3. 설계

1. /mcp 핸들러는 호출 때 이름으로 도구 목록의 지금 실행기를 찾아 부른다. 목록에 없으면 도구 없음 오류 결과를 낸다. 노출 판정, 호출 제한, 훅, 결과 자르기는 지금과 같다
2. 바깥 MCP 실행기는 등록될 때의 테넌트를 기억하고, 호출 중 재연결은 그 테넌트로 돈다. MCP 서버 등록은 그 테넌트 DB 에 있으므로 부른 쪽 테넌트가 아니라 등록 테넌트가 맞다. 재연결이 끝나면 부른 쪽 테넌트 상태로 되돌린다
3. 재연결은 지금처럼 등록 config 로 새 연결을 만들므로 CR-145 머리글이 그대로 실린다. 바뀌지 않는다

## 4. 범위 밖

- /mcp 핸들러 스레드에 요청 문맥(테넌트, 워크스페이스 경로, 에이전트 ID)이 넘어가지 않는 일반 문제. 2.2 와 같은 원인이지만 이번에는 재연결만 등록 테넌트로 고정한다. 다른 영향은 확인하지 않았다
- 재연결 뒤 도구 설명(입력 스키마)이 바뀌어도 /mcp 목록이 옛 설명을 유지하는 점. 이름이 같으면 다시 싣지 않는 지금 동작 그대로다
- 옛 서버(59.8.160.12) 반영. 다른 테넌트가 쓰는 운영이라 따로 승인받는다

## 5. 영향

모든 테넌트의 /mcp 도구 호출(CLI 연결 tool_mode AIMBASE, HYBRID)과 바깥 MCP 실행기의 호출 중 재연결.
워크플로우, API 채팅 경로는 호출 중 재연결의 테넌트만 등록 테넌트로 고정되고 나머지는 같다.
DB 스키마 변경은 없다.

## 6. 확인

### 6.1 단위시험 (2026-10-12)

- ServerMcpConfigTest 1건(실린 핸들러가 호출 때 이름으로 실행기를 찾는다), ServerMcpToolDispatcherTest 신규 2건(바뀐 실행기를 쓴다, 없어진 도구는 오류 결과), MCPToolExecutorLazyReconnectTest 신규 2건(테넌트 없는 스레드의 재연결이 등록 테넌트로 돈다, 실패해도 부른 쪽 테넌트로 되돌린다)
- 기존 MCPServerManagerTest, MCPServerClientHeadersTest 포함 41건 통과

### 6.2 로컬 (2026-10-12, BE 8280, tenant_dev, 열쇠를 요구하는 가짜 앱 MCP 서버)

가짜 서버는 Streamable 로 붙고 세션을 메모리에 두어, 재기동하면 옛 세션에 404 를 준다(앱 재기동과 같다). 헬스체크 재연결은 꺼 두었다.

| 순번 | 순서 | 수정 전 | 수정 후 |
|------|------|------|------|
| 1 | 등록, 도구 탐색, /mcp 호출 | 성공 | 성공 |
| 2 | 앱 재기동, REST 재연결, /mcp 호출 | "Client failed to initialize by explicit API call" | 성공 |
| 3 | 앱 중단 중 /mcp 호출 | 실패 | 실패(앱이 없으므로 맞다) |
| 4 | 앱 다시 기동 뒤 /mcp 호출 | 실패 | 성공 |
| 5 | 이어서 한 번 더 | 실패 | 성공 |

- 수정 전 로그: 옛 실행기 호출이 실패한 뒤 호출 중 재연결이 `relation "mcp_servers" does not exist`(master DB 조회)로 실패했다(16건). 그 재연결이 REST 재연결로 만든 새 연결을 닫았다
- 수정 후 로그: 같은 오류 0건. 4번에서 호출 중 재연결이 등록 테넌트로 돌아 바로 다시 붙었다
- 가짜 서버가 받은 요청 17건 모두 맞는 열쇠였다. 재연결 뒤에도 CR-145 머리글이 실린다

### 6.3 반영 jar

- 지금 AWS jar(2026-10-11 16:02 빌드)는 커밋만으로 다시 만들 수 없다. 커밋된 OrchestratorEngine 이 커밋 안 된 ChatRunRecorder, ChatRequest 변경에 기대어 HEAD 단독 빌드가 실패한다
- 작업 트리의 BE 미커밋 파일 129개 중 그 뒤에 바뀐 것이 없고, 작업 트리를 따로 복사해 만든 jar 가 AWS jar 와 1430개 항목이 같다. 다른 2개(SDK 내부 jar)는 빌드 시각만 다르고 속 파일 59개가 같다
- 그 복사본에 이 수정만 얹은 jar 는 AWS jar 와 ServerMcpConfig, ServerMcpToolDispatcher, MCPToolExecutor, MCPServerManager 클래스 4개만 다르다

### 6.4 운영

(반영 뒤 채운다)
