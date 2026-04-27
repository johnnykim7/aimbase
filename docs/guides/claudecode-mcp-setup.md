# Aimbase × Claude Code (MCP) 연동 가이드

이 문서는 **Claude Code 사용자**가 자기 PC 의 `aimbase-agent` 와 Aimbase 서버를 연결해 운용할 때 필요한 셋업을 안내한다.

- 대상: Claude Code, 기타 MCP 클라이언트 사용자
- 목적: 본인 PC 의 Claude Code CLI 가 본인 정액 플랜을 사용하면서 Aimbase 서버의 워크플로우/도구 거버넌스를 받도록 연결
- 관련 CR: CR-071 (3경로 통일), CR-042 (aimbase-agent 독립 모듈), CR-044 (CLI 두뇌 + Aimbase 손발)

---

## 1. 그림

```
[사용자 PC]
   ├─ aimbase-agent (--runner-mode)        ← Claude Code CLI 를 자식 프로세스로 spawn
   │     listen 127.0.0.1:8290 (예시)
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

## 3. aimbase-agent `--runner-mode` 기동

```bash
java -jar aimbase-agent.jar \
  --runner-mode \
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
    enabled: true
    api-key: <RUNNER_API_KEY>           # 호출자(Aimbase 서버) X-Api-Key 인증
    default-model: claude-sonnet-4-5
    claude-binary: /usr/local/bin/claude
    max-workers: 5
    aimbase-mcp-jar: /opt/aimbase-agent/aimbase-agent.jar  # AIMBASE/HYBRID 도구 모드 시 MCP 서버 jar
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

본인 PC Claude Code 가 Aimbase 서버의 MCP 도구를 호출할 때:

```jsonc
// ~/.claude/.mcp.json
{
  "mcpServers": {
    "aimbase": {
      "url": "https://aimbase.your-org.com/mcp/sse",
      "headers": {
        "X-API-Key": "<테넌트 키>",
        "X-Aimbase-Agent-Id": "a3bc1..."  // ← 위에서 발급받은 agent-id
      }
    }
  }
}
```

`X-Aimbase-Agent-Id` 헤더가 누락되면 `adapter=anthropic-cli` 의 워크플로우 호출은 400.
일반 Aimbase API 호출(REST)도 가능. Anthropic/OpenAI 등 다른 어댑터는 헤더 없이 동작한다.

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
| 사용자 PC 꺼져 있음 | Runner 미응답 → 호출 실패 (정직). 폴백 정책은 별도 CR (자동화 미포함) |

---

## 8. 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| v1.0.0 | 2026-04-27 | CR-071 초판 — Claude Code MCP 연동 + aimbase-agent `--runner-mode` 셋업 |
