# Aimbase × Claude Code (MCP) 연동 가이드

이 문서는 **Claude Code 사용자**가 자기 PC 의 `aimbase-agent` 와 Aimbase 서버를 연결해 운용할 때 필요한 셋업을 안내한다.

- 대상: Claude Code, 기타 MCP 클라이언트 사용자
- 목적: 본인 PC 의 Claude Code CLI 가 본인 정액 플랜을 사용하면서 Aimbase 서버의 워크플로우/도구 거버넌스를 받도록 연결
- 관련 CR: CR-071 (3경로 통일), CR-042 (aimbase-agent 독립 모듈), CR-044 (CLI 두뇌 + Aimbase 손발)

---

## 1. 그림

```
[사용자 PC]
   ├─ aimbase-agent (SERVLET 모드, 기본)    ← Claude Code CLI 를 자식 프로세스로 spawn
   │     listen 127.0.0.1:8290 (예시)       (CR-073 — --runner-mode 플래그 폐지, § 3 참조)
   ├─ Claude Code 본인 설정 (~/.claude/.mcp.json)
   └─ 작업 도구 (소비자앱 브라우저, Claude Code, ...)
                    │
                    ↓ HTTP (X-Api-Key + X-Aimbase-Agent-Id)
[Aimbase 서버]
   └─ ClaudeCliAdapter → ClaudeCliRunner (사용자 PC) → Claude Code CLI
```

---

## 2. 사전 준비

### 2-1. Claude Code CLI 설치 + 로그인

```bash
# 설치
brew install anthropic/claude/claude       # macOS
# 또는 npm i -g @anthropic-ai/claude-code (Node 환경)

# 로그인 (Max/Pro 구독 또는 API Key)
claude login
# → ~/.claude/ 에 OAuth 토큰 저장됨
```

> 이 디렉토리(`~/.claude/`) 가 `claude_config_dir` 로 지정된다. 상이한 사용자/계정 분리가 필요하면 `--claude-config-dir <path>` 로 지정.

### 2-2. aimbase-agent 설치

```bash
# DMG / MSI 설치 또는 jar 직접 다운로드 (CR-042)
java -jar aimbase-agent.jar --help
```

### 2-3. Aimbase 서버에 agent 등록 + agent-id 발급

`POST /api/v1/agents/register` 호출. 본인 PC 가 Runner 모드로 동작함을 metadata 에 반드시 포함:

```bash
curl -X POST https://aimbase.your-org.com/api/v1/agents/register \
  -H "X-API-Key: <테넌트 키>" \
  -H "Content-Type: application/json" \
  -d '{
    "agentName": "user-laptop",
    "publicAddress": "your-pc-public-ip",
    "mcpPort": 8190,
    "metadata": {
      "runnerEndpoint": "http://your-pc-public-ip:8290",
      "runnerApiKeyHash": "<sha256 of your runner key>"
    }
  }'
# → { "id": "a3bc1...", "agentName": "user-laptop", ... }
```

응답 `id` 가 발급된 **agent-id**. 모든 호출 헤더에 사용한다.

> 사내망 / NAT 환경에서는 `runnerEndpoint` 를 STUN/TURN 경유 주소로 등록하거나, `aimbase-agent` 의 자동 등록 흐름(CR-041 RemoteToolDiscovery)을 사용해도 된다.

---

## 3. aimbase-agent 기동

> **CR-073 (v8.7.0)** — `--runner-mode` 플래그 폐지. `--mcp-stdio` 외 모든 진입은 SERVLET 모드 (HTTP 포트 항상 OPEN).
> `--runner-mode` 가 박혀있어도 무시되며 정상 기동된다 (후방 호환).

```bash
java -jar aimbase-agent.jar \
  --aimbase.runner.api-key=<RUNNER_API_KEY> \
  --aimbase.runner.default-model=claude-sonnet-4-5 \
  --aimbase.runner.claude-binary=/usr/local/bin/claude \
  --aimbase.runner.max-workers=5 \
  --server.port=8290
```

또는 `~/.aimbase-agent/config/application.yml` 에 영구 설정:

```yaml
aimbase:
  runner:
    api-key: <RUNNER_API_KEY>           # 호출자(Aimbase 서버) X-Api-Key 인증
    default-model: claude-sonnet-4-5
    claude-binary: /usr/local/bin/claude
    max-workers: 5
    aimbase-mcp-jar: /opt/aimbase-agent/aimbase-agent.jar  # AIMBASE/HYBRID 도구 모드 시 SDK MCP 서버 jar
    # CR-072: 서버 도구 26개를 CLI 가 추가로 호출하도록 mcpServers 에 'aimbase-server' 박기.
    # 미지정 시 기존 키 'aimbase' (SDK 만) 단독 출력 — 호환 모드.
    server-mcp-base-url: https://aimbase.your-org.com
    server-mcp-api-key: <테넌트 X-API-Key>
    server-mcp-agent-id: <agent-id>
server:
  port: 8290
```

기동 확인:
```bash
curl http://localhost:8290/v1/health -H "X-Api-Key: <RUNNER_API_KEY>"
# → { "status": "UP", "active_runs": 0, "max_workers": 5, "uptime_seconds": ... }
```

---

## 4. Claude Code 자체에서 Aimbase MCP 사용

> **CR-072 (v8.7.0)** — 단일 `aimbase` 키 → **다중 mcpServers** (`aimbase-local` + `aimbase-server`) 로 확장.
> SDK 14개 (사용자 PC) + 서버 도구 (`web_search`, `http_request`, `send_message`, `schedule_cron`, `notebook_edit`, `lsp`, `bash`, `file_write`, `download_file` 등) 를 모두 CLI 에 노출.
> 호환 모드: `aimbase-server` 미지정 시 기존 키 `aimbase` 단독 (SDK 만) — CLI 호출 prefix 깨짐 방지.
>
> **CR-104 / CR-110** — 서버 도구 노출 개수는 28→42 로 확장되었고(`file_write`/`bash`/`download_file` 등 추가), 이후 화이트리스트가 코드 하드코딩에서 `global_config.mcp.cli-exposed-tools`(CSV, 42개) **런타임 단일 소스**로 외부화됐다. CLI 경로(`/mcp`)와 API 어댑터 경로가 같은 목록을 참조하므로 **두 경로의 도구 집합이 동일**하다. 목록 변경은 § 4 하단 참조.

본인 PC Claude Code 가 Aimbase SDK + 서버 도구를 모두 호출할 때:

```jsonc
// ~/.claude/.mcp.json
{
  "mcpServers": {
    "aimbase-local": {
      "command": "java",
      "args": ["-jar", "/opt/aimbase-agent/aimbase-agent.jar", "--mcp-stdio"]
    },
    "aimbase-server": {
      "type": "http",
      "url": "https://aimbase.your-org.com/mcp",
      "headers": {
        "X-API-Key": "<테넌트 키>",
        "X-Aimbase-Agent-Id": "a3bc1..."
      }
    }
  }
}
```

> **CR-124 (2026-07-22)**: transport 가 SSE → **Streamable HTTP** 로 바뀌었다.
> `"type": "http"`, URL 은 `/mcp/sse` → **`/mcp`**(하위경로 없는 단일 엔드포인트).
> 배경: Claude CLI 2.1.x 는 프로토콜 `2025-11-25` 를 요구하는데 **SSE transport 는
> MCP Java SDK 2.0.0 에서도 `2024-11-05` 하나만 광고**해 협상이 실패하고 도구가
> 0개로 고정된다. Streamable HTTP 만 `2025-11-25` 를 지원한다.

CLI 가 두 서버 모두에 connect 후 도구 카탈로그를 prefix 분리해 합친다 — `mcp__aimbase-local__file_read` vs `mcp__aimbase-server__web_search`.

`aimbase-server` 의 `/mcp` 가 받는 인증/라우팅 헤더:

| 헤더 | 처리 필터 | 용도 |
|---|---|---|
| `X-API-Key` | `ApiKeyAuthenticationFilter` | tenant_id 자동 결정 (필수) |
| `X-Aimbase-Agent-Id` | `AgentIdRequestFilter` | 세션 식별 + Hook 컨텍스트 (선택) |

서버 측 거버넌스 (`/mcp` 진입 도구 호출):
- **PRE/POST_TOOL_USE Hook** ✓ 적용
- **Rate Limit** ✓ 적용 (`mcp.rate-limit.requests-per-minute`, 기본 60/min, 테넌트 단위)
- **화이트리스트** ✓ `global_config.mcp.cli-exposed-tools` (CSV, 42개) 에 든 도구만 노출 (CR-110). `parse_document` 및 내부 전용 6개(`team_create`/`team_delete`/`enter_plan_mode`/`exit_plan_mode`/`verify_plan_execution`/`temp_cleanup`)는 의도적 제외
- **PolicyEngine / max_iterations / 풀세트 Hook** ✗ CR-050 트레이드오프 계승

도구 노출 on/off + 화이트리스트:
```yaml
mcp:
  server-exposure:
    enabled: true     # false 면 /mcp 도구 0개 노출
  rate-limit:
    requests-per-minute: 60
```

- **화이트리스트 변경 (CR-110)**: 노출 도구 목록은 `global_config.mcp.cli-exposed-tools`(CSV) 단일 소스로 관리. **제거는 런타임 즉시 반영**(실행 게이트 차단), **추가는 재기동 시** `/mcp` 노출에 반영. `PlatformSettingsService` 5분 캐시. V66 master seed(42개)가 코드 상수 `McpExposurePolicy.DEFAULT_FALLBACK`과 정합 유지

---

## 5. 소비자앱(브라우저)에서 Aimbase 호출

소비자앱이 Aimbase API 를 호출할 때 헤더에 사용자별 `X-Aimbase-Agent-Id` 를 박는다:

```http
POST /api/v1/chat
X-API-Key: <테넌트 키>
X-Aimbase-Agent-Id: a3bc1...
Content-Type: application/json

{
  "session_id": "...",
  "messages": [...]
}
```

로그인된 사용자별로 `agent-id` 를 매핑(예: 사용자 프로필 또는 환경변수 `AIMBASE_AGENT_ID`)해 모든 요청에 자동 주입.

---

## 6. ToolMode (CR-069)

Connection 의 `config.tool_mode` 값에 따라 CLI 가 사용할 수 있는 도구 범위가 달라진다:

| 모드 | CLI 옵션 | 의미 |
|---|---|---|
| `AIMBASE` (기본) | `--strict-mcp-config --tools ""` + Aimbase MCP 만 | Aimbase SDK 도구만 노출 → 거버넌스 100% 적용 |
| `NATIVE` | `--bypass-permissions` + 기본 도구 | CLI 자체 도구 자율 사용 (빠름, 무통제) |
| `HYBRID` | strict + Aimbase MCP + 화이트리스트 | 둘 다 노출 |

용도별로 connection 을 분리 등록해 워크플로우에서 골라 쓴다.

---

## 7. 트러블슈팅

| 증상 | 원인 / 조치 |
|---|---|
| `400 X-Aimbase-Agent-Id header required for ClaudeCliAdapter` | 호출자에서 헤더 누락. 위 § 4·5 참조 |
| `400 No active ClaudeCliRunner for agent-id ...` | agent 가 비활성(heartbeat 5분 초과) 또는 `runner_capability=false`. agent 재등록 필요 |
| `401 Invalid X-Api-Key` (Runner 응답) | aimbase-agent 의 `aimbase.runner.api-key` 와 connection 의 `runner_api_key` 불일치 |
| Worker 큐 대기 (BIZ-100) | run 당 5개 상한. 동시 요청 많으면 `--max-workers` 증가 또는 분산 |
| `No such tool: mcp__aimbase-server__<x>` (CR-104) | 해당 도구가 `global_config.mcp.cli-exposed-tools` 화이트리스트에 없음. CSV 에 추가 후 **재기동**(추가는 재기동 시 `/mcp` 반영). 제거 방향은 런타임 즉시 |
| **MCP 상태가 `pending` 에서 안 바뀌고 도구 0개** (CR-124) | `.mcp.json` 이 옛 SSE 설정(`"type":"sse"`, `/mcp/sse`). **`"type":"http"` + `/mcp`** 로 교체. 서버 로그에 `Client requested unsupported protocol version: 2025-11-25 ... suggest the 2024-11-05` 가 찍히면 확정. SSE 는 MCP Java SDK 2.0.0 에서도 `2024-11-05` 만 광고해 협상 불가 |
| 배포 직후 도구 개수가 모자람 (원격 도구 누락) | 소비앱 원격 도구는 agent 재등록 → `RemoteToolDiscovery` 30초 주기 동기화(CR-121)를 거쳐야 붙는다. **배포 후 ~90초** 뒤 재확인. `docker compose logs api \| grep 'tool exposed'` 로 반영 시각 확인 |
| AGENT_CALL 이 "진행 중" 으로 멈춤 (turn timeout) | CLI worker hang 가능. agent 로그 `turn timeout after Ns` 확인 + agent `spring.mvc.async.request-timeout`(기본 600s, `AIMBASE_AGENT_ASYNC_TIMEOUT_MS`) vs BE `anthropic-cli.http-timeout-seconds` 정렬 확인(CR-111). 멈춘 run 은 `POST /workflows/runs/{runId}/cancel?force=true` 강제 중지 후 `/rerun`(CR-116). 좀비 `claude` 프로세스 누적 시 `pkill`(CR-109/114) |
| 사용자 PC 꺼져 있음 | Runner 미응답 → 호출 실패 (정직). 폴백 정책은 별도 CR (자동화 미포함) |

---

## 8. 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| v1.2.0 | 2026-06-18 | **현행화 — 도구 노출 26→42 + 화이트리스트 외부화 + timeout/좀비 트러블슈팅**. § 1 그림 `--runner-mode` → SERVLET 모드(CR-073 폐지 반영). § 4 서버 도구 노출 개수 26 고정 표기 제거 — CR-104(28→42, `file_write`/`bash`/`download_file` 추가) + CR-110(`global_config.mcp.cli-exposed-tools` CSV 단일 소스, 제거=즉시/추가=재기동) 반영, 화이트리스트 항목·on/off 블록 갱신. § 7 트러블슈팅에 `No such tool`(CR-104) + AGENT_CALL turn timeout/force-cancel/rerun/좀비(CR-111/116/109/114) 추가. 코드 실측(`McpExposurePolicy`/V66 SQL/`ClaudeCliCommandBuilder`). 신규 코드 없음 — 문서만 |
| v1.1.0 | 2026-04-28 | CR-072 + CR-073 — 다중 mcpServers (`aimbase-local` + `aimbase-server`) 로 SDK 14개 + 서버 도구 26개 노출. `--runner-mode` 플래그 폐지 (후방 호환). `mcp.server-exposure.enabled` / `mcp.rate-limit.requests-per-minute` 키 추가 |
| v1.0.0 | 2026-04-27 | CR-071 초판 — Claude Code MCP 연동 + aimbase-agent `--runner-mode` 셋업 |
| v1.1.0 | 2026-04-28 | CR-072 + CR-073 — 다중 mcpServers (`aimbase-local` + `aimbase-server`) 로 SDK 14개 + 서버 도구 26개 노출. `--runner-mode` 플래그 폐지 (후방 호환). `mcp.server-exposure.enabled` / `mcp.rate-limit.requests-per-minute` 키 추가 |
