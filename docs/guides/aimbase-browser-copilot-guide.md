# Aimbase 브라우저 코파일럿 연동 가이드

> **대상**: 소비앱(WES/OMS/MALL 등) 개발자 — 복잡한 웹 화면을 aimbase 가 작업자와 **대화하며 함께 조작**하게 만들려는 경우.
> **선행 문서**: [연동 방식 선택 가이드](./aimbase-integration-pattern-guide.md) — 워크플로우/자율대화 선택 기준. 이 문서는 그 중 "자율 대화 + 브라우저 조작" 패턴의 각론이다.
> **버전**: v1.0.0 (2026-07-26)

---

## 0. 30초 요약

| 항목 | 답 |
|---|---|
| 소비앱이 만드는 것 | **채팅 화면 하나.** `POST /chat/completions` 를 `session_id` 로 이어 부르면 끝 |
| 워크플로우로 만드나 | **아니다.** 절차를 미리 못 박는 게 전제라 자율 대화가 맞다 |
| 브라우저는 어디 뜨나 | **작업자 PC.** 서버가 아니다 — 사람이 봐야 개입한다 |
| 시나리오는 어디 적나 | 커넥션의 **`system_prompt_override`**. 이게 실질적 본체다 |
| 신규 개발량 | 소비앱 = 채팅 UI. aimbase = 0 (배선 완료) |

**핵심**: 이 패턴에서 "개발"의 대부분은 코드가 아니라 **프롬프트 작성**이다.

---

## 1. 언제 이 패턴인가

**맞는 경우**
- 화면이 복잡해 신입이 절차를 익히기 어렵다
- 분기가 많아 시나리오를 전부 미리 못 짠다
- 사람이 최종 판단해야 하는 지점이 곳곳에 있다
- 기존 화면을 그대로 두고 보조만 붙이고 싶다 (화면 개편 불가/부담)

**안 맞는 경우**
- 절차가 완전히 고정 → **워크플로우**가 맞다
- 사람 개입이 아예 필요 없다 → 배치/API 직접 호출이 맞다
- 대상이 웹 화면이 아니다 (설치형 클라이언트) → Playwright 불가

> **판단 기준**: "매번 다르게 흘러가고, 사람이 봐야 하는가?" 그렇다면 이 패턴.

---

## 2. 전체 그림

```
[작업자 모니터]
 ├─ 소비앱 화면 (채팅창)  ← 작업자가 대화하는 곳
 └─ Chrome (대상 업무화면) ← 실제 조작이 일어나는 곳. Playwright 가 띄움

[흐름]
소비앱 ──POST /chat/completions──> aimbase 서버
                                      │
                                      ▼ (TURN 릴레이)
                              작업자 PC 의 Runner
                                      │ spawn
                                      ▼
                                 Claude CLI
                                      │ MCP
                                      ▼
                            Playwright ──> Chrome
```

작업자는 채팅창에 답하고, Chrome 창에서 결과를 눈으로 확인한다.
**로그인은 첫 화면에서 사람이 직접 한다** — 세션 승계를 구현할 필요가 없다.

---

## 3. 소비앱이 구현할 것

### 3.1 API 호출 (이게 전부다)

**1턴 — 시작**
```bash
POST /api/v1/chat/completions
X-API-Key: <테넌트 API 키>
X-Tenant-Id: <테넌트 ID>
Content-Type: application/json

{
  "model": "claude-sonnet-4-5",
  "connection_id": "cli-runner-wes-pc-01",
  "messages": [
    { "role": "user", "content": "3번 반품 건 처리 시작해줘" }
  ]
}
```

**응답**
```json
{
  "success": true,
  "data": {
    "session_id": "21a14c0b-142d-4f0c-b7a9-66439d7c1edc",
    "content": [{ "type": "text",
      "text": "반품 상세 화면을 열었습니다. 사유가 '파손'인데 사진상 미개봉입니다.\n어느 쪽으로 처리할까요?" }]
  }
}
```

**2턴 이후 — `session_id` 를 넣는다**
```json
{
  "model": "claude-sonnet-4-5",
  "connection_id": "cli-runner-wes-pc-01",
  "session_id": "21a14c0b-142d-4f0c-b7a9-66439d7c1edc",
  "messages": [{ "role": "user", "content": "파손으로 처리해줘" }]
}
```

`session_id` 만 유지하면 **대화 맥락과 브라우저 상태가 함께 이어진다.**
페이지를 다시 열거나 상태를 복원할 필요가 없다.

> `model` 에 `"auto"` 를 쓰면 CLI 어댑터에서 400 이다. 구체 모델명을 넣는다.

### 3.2 UI 요구사항

최소한 이 정도면 동작한다:
- 메시지 목록 + 입력창 (일반 채팅 UI)
- `session_id` 를 화면 상태에 보관
- "새 작업 시작" = `session_id` 를 버리고 1턴부터

**응답이 오래 걸린다.** 브라우저 조작이 섞이면 턴당 30초~수분이다.
진행 표시를 두거나 `stream: true` 로 스트리밍을 받는다.

> 위젯(`chat-widget-embed`)을 그대로 써도 된다 — 채팅 UI 를 새로 만들지 않아도 되는 경우가 많다.
> [위젯 임베드 가이드](./embed-chat-widget.md) 참조.

---

## 4. 시나리오 = 시스템 프롬프트

**이 패턴에서 가장 중요한 작업.** 코드가 아니라 여기에 업무를 적는다.
커넥션의 `config.system_prompt_override` 에 넣는다.

### 4.1 골격

```
당신은 <업무명> 작업을 작업자와 함께 수행하는 보조자입니다.
브라우저는 이미 열려 있고, 작업자가 화면을 보고 있습니다.

## 작업 절차
1. <첫 단계>
2. <다음 단계>
...

## 반드시 사람에게 물어야 하는 지점
- <판단이 갈리는 조건> → 물어보고 멈춘다
- 되돌릴 수 없는 확정 버튼을 누르기 전 → 항상 확인받는다

## 화면 조작 규칙
- browser_snapshot 으로 현재 화면을 먼저 읽고 판단한다
- 요소는 snapshot 의 ref 로 지목한다 (셀렉터를 추측하지 않는다)
- 화면이 예상과 다르면 임의로 진행하지 말고 상황을 설명하고 묻는다

## 답변 규칙
- 한국어로 간결하게
- 무엇을 했는지 한 줄로 알리고, 물을 게 있으면 명확히 묻는다
```

### 4.2 작성 요령

**"물어야 하는 지점"이 이 패턴의 핵심이다.** 여기를 촘촘히 쓸수록 안전해진다.
반대로 비워두면 CLI 가 알아서 진행해버린다.

**절차를 지나치게 세밀히 쓰지 않는다.** 화면 구조가 바뀌어도 CLI 가 snapshot 을
읽고 적응하는 게 이 방식의 장점인데, 클릭 좌표나 셀렉터를 박아두면 그 장점이 사라진다.
"무엇을 달성해야 하는지"를 쓰고 "어떻게 클릭하는지"는 맡긴다.

**금지 사항을 명시한다.** "임의로 확정하지 마라", "새 탭을 열지 마라",
"다른 메뉴로 이동하지 마라" 같은 경계가 사고를 막는다.

---

## 5. 사전 준비 (인프라)

> 소비앱 개발자가 직접 할 일은 아니지만, 무엇이 필요한지 알아야 일정이 잡힌다.

### 5.1 작업자 PC 마다 Runner

| 항목 | 값 |
|---|---|
| Java | **21 이상** (17 이면 UnsupportedClassVersionError) |
| Node | **20 이상** (Playwright MCP 요구. 18 이면 거부) |
| Chrome | 설치 필요 |
| 배포물 | `aimbase-agent-*.jar` |

기동 환경변수:
```bash
AGENT_NAME=wes-pc-01                      # PC 마다 고유
AGENT_TENANT_ID=<테넌트 ID>                # 없으면 등록 400
AGENT_AIMBASE_URL=http://<aimbase>:8280
AGENT_API_KEY=<테넌트 API 키>
AGENT_TURN_ENABLED=true
AGENT_TURN_TRANSPORT=TCP
AGENT_TURN_ALLOWED_PEER_IPS=<서버 공인IP>,<서버 docker 브리지IP>
SERVER_PORT=8290
AIMBASE_RUNNER_ENABLED=true
AIMBASE_RUNNER_API_KEY=<러너 키>
AIMBASE_RUNNER_EXTRA_MCP_SERVERS_JSON='{"playwright":{"command":"<npx 절대경로>","args":["-y","@playwright/mcp@latest","--browser","chrome","--user-data-dir","<고정 프로필 경로>"]}}'
```

> **`AGENT_TURN_ALLOWED_PEER_IPS` 에 docker 브리지 IP 를 빼먹으면 안 된다.**
> coturn 과 aimbase 가 같은 호스트면 api 컨테이너가 브리지 IP(예: `172.27.0.2`)로
> 도착해 `peer has no permission` 으로 거부된다. 확인:
> `docker inspect aimbase-api-1 --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'`

> **`--user-data-dir` 는 반드시 고정 경로.** 워커가 여러 개 떠도 같은 프로필을
> 써야 브라우저가 공유된다. 생략하면 임시 디렉터리가 매번 생겨 로그인이 풀린다.

### 5.2 커넥션 (PC 하나당 하나)

```json
POST /api/v1/connections
{
  "id": "cli-runner-wes-pc-01",
  "name": "WES PC-01 브라우저 코파일럿",
  "adapter": "anthropic-cli",
  "type": "llm",
  "config": {
    "model": "claude-sonnet-4-5",
    "tool_mode": "HYBRID",
    "agent_name": "wes-pc-01",
    "runner_api_key": "<러너 키>",
    "system_prompt_override": "<§4 에서 작성한 프롬프트>"
  }
}
```

`tool_mode` 는 **HYBRID** — 네이티브 도구(자율 탐색)와 MCP 도구(Playwright + aimbase)를
함께 쓴다. NATIVE 는 MCP 를 아예 안 붙이므로 Playwright 가 죽는다.

### 5.3 PC 지목 방법

작업자 A 가 시작하면 A 의 PC 에서 브라우저가 떠야 한다.
**소비앱이 로그인 사용자에 따라 `connection_id` 를 고른다** — 가장 단순하고 확실하다.

```
사용자 계정 ─(소비앱 매핑 테이블)→ connection_id ─(agent_name)→ 그 PC 의 Runner
```

> `user_ref` 자동 라우팅(CR-075)도 있으나 위젯 토큰 기반이라, 자체 채팅 화면을
> 만드는 경우엔 커넥션 매핑이 맞다.

---

## 6. 안전장치

### 6.1 위험 동작 승인

**정책 엔진으로 잡기 어렵다.** Playwright 는 모든 클릭이 `browser_click` 하나라
"무엇을 누르는지"로 정책을 걸 수 없다.

| 방법 | 강도 | 비고 |
|---|---|---|
| 시스템 프롬프트에 "확정 전 반드시 물어라" | 중 | 현실적. 대부분 이걸로 충분 |
| 확정 API 만 별도 MCP 도구로 분리 + `REQUIRE_APPROVAL` | 강 | 확실하지만 도구 개발 필요 |
| 계정 권한 자체를 제한 | 강 | 조회 전용 계정으로 로그인시키면 쓰기 불가 |

되돌릴 수 없는 동작(재고 확정, 환불, 삭제)이 있다면 **프롬프트만 믿지 말고**
계정 권한이나 별도 창구를 함께 건다.

### 6.2 노출 도구 제한

Playwright MCP 는 도구가 24개다. aimbase 도구까지 합치면 컨텍스트 부담이 커진다.
[CR-129 실측](./aimbase-integration-pattern-guide.md#7-노출-도구-제한-exposed_tools--성능에-직결)에서
110개→10개로 줄여 72초→19초(3배)가 나왔다.

커넥션 `config.exposed_tools` 로 필요한 것만 남긴다. 브라우저 조작이면 보통:
```
browser_navigate, browser_snapshot, browser_click, browser_type,
browser_select_option, browser_wait_for, browser_take_screenshot
```

> CLI 가 `ToolSearch` 로 필요한 것만 로드하긴 하지만, 초기 노출량 자체를 줄이는 게 낫다.

---

## 7. 알려진 제약

| 제약 | 내용 | 대응 |
|---|---|---|
| 접근성 트리 의존 | 화면이 canvas/커스텀 위젯이면 snapshot 이 비어 조작 불가 | 도입 전 대상 화면으로 **먼저 확인** |
| 응답 지연 | 턴당 30초~수분 | 스트리밍 또는 진행 표시 |
| 워커 동시 실행 | run 당 최대 5개(BIZ-100). 같은 프로필을 동시에 열면 락 충돌 | 대화형은 본질상 순차라 실용상 문제 없음 |
| 실행 기록 | CLI 채팅은 실행 내역에 남는다(CR-130) | 도구 궤적까지 조회 가능 |
| PC 배포 | Windows 서비스 패키징 미구현 | 현재는 jar 직접 실행 |
| HTTP MCP 방식 | `--port` 서버 모드는 Runner spawn CLI 에서 연결 실패(원인 미규명) | **stdio 방식을 쓴다** |

---

## 8. 도입 순서 (권장)

1. **대상 화면 확인** — Playwright 가 그 화면의 접근성 트리를 읽는지 먼저 본다.
   여기서 막히면 이 패턴 전체가 성립하지 않으므로 **가장 먼저** 깬다.
2. **업무 하나 선정** — 가장 자주 하고 절차가 명확한 것 하나.
3. **시스템 프롬프트 작성** — §4. 실제 작업 순서를 그대로 글로 쓴다.
4. **PC 1대로 시험** — Runner + 커넥션 1개. `curl` 로 2턴 대화를 돌려본다.
5. **채팅 UI 연결** — 소비앱에서 §3 대로 호출.
6. **작업자 시범 사용** — 프롬프트를 다듬는다. 이 단계가 가장 오래 걸린다.
7. **PC 확대** — Runner 배포 + 커넥션 추가.

> 1번을 건너뛰지 않는다. 화면이 안 읽히면 나머지가 전부 무의미하다.

---

## 9. 트러블슈팅

| 증상 | 원인 | 확인/조치 |
|---|---|---|
| `cli_agent_offline` | Runner 미기동 또는 `runner_capability=false` | Runner 로그 + `agent_registry` 조회 |
| `TURN_BROKEN_PIPE` | CreatePermission 에 docker 브리지 IP 누락 | coturn 로그의 `tcp accepted from:` 소스 IP 확인 |
| `browser_*` 도구 없음 | MCP 연결 실패 | Runner 로그 `mcp_servers=[{name=playwright, status=?}]` |
| 설정을 바꿨는데 안 먹음 | **살아있는 워커 재사용** | Runner 재기동. BE 재기동은 무의미 |
| "브라우저가 사용 중" | 프로필 락 충돌 | `--user-data-dir` 고정 확인 |
| 400 Bad Request | `model: "auto"` | 구체 모델명 사용 |

> **로그 위치**: `~/.aimbase-agent/logs/agent.log` (stdout 아님).
> `tail` 하기 전에 **타임스탬프를 확인**한다 — 옛 워커 기록을 현재 상태로 오독하기 쉽다.

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|---|---|---|
| v1.0.0 | 2026-07-26 | 최초 작성. 브라우저 코파일럿 패턴 — 소비앱 연동 방법, 시스템 프롬프트 작성법, Runner/커넥션 준비, 안전장치, 제약, 도입 순서. e2e 검증(서버→TURN→PC Runner→CLI→Playwright→Chrome, 2턴 대화 브라우저 상태 유지) 기반 |
