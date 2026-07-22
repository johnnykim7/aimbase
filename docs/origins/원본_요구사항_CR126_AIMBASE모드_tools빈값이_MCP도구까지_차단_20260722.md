# CR-126 원본 — AIMBASE 모드의 `--tools ""` 가 MCP 도구까지 전부 차단

작성일: 2026-07-22
발견 경위: bp-wes(CR-153) 위젯 응답 실패를 추적하다 확인. 1차 원인(전용 러너 CR-124 배포 누락)을
해소한 뒤에도 도구가 0개로 남아 CLI 인자를 직접 실측하는 과정에서 드러났다.

> **이것은 CR-124 가 만든 결함이 아니다.** `--tools ""` 봉인 방식은 그 이전부터 있었고,
> CR-124 배포 누락에 가려 보이지 않다가 배포가 정상화되면서 단독으로 드러났다.

---

## 1. 문제

`ClaudeCliCommandBuilder` 는 AIMBASE 모드에서 **CLI 네이티브 도구를 봉인**할 목적으로
`--tools ""` 를 넘긴다.

```java
// cli-runner/src/main/java/com/platform/runner/claudecli/ClaudeCliCommandBuilder.java:263-266
} else if (toolMode == ToolMode.AIMBASE && sealNativeTools) {
    cmd.add("--tools");
    cmd.add("");
}
```

주석에 적힌 의도는 이렇다:

```
 * - AIMBASE: --tools "" (네이티브 봉인) + --strict-mcp-config + --mcp-config <json>
```

**의도는 "네이티브만 봉인, MCP 는 유지" 인데, CLI 의 실제 동작은 "전부 봉인" 이다.**

Claude CLI 2.1.197 `--help`:

```
  --tools <tools...>   Specify the list of available tools from the built-in set.
                       Use "" to disable all tools, "default" to use all tools,
                       or specify tool names (e.g. "Bash,Edit,Read").
```

`""` 는 built-in 뿐 아니라 **MCP 도구를 포함한 all tools** 를 끈다.

---

## 2. 증상

AIMBASE 모드로 동작하는 모든 러너에서 **MCP 도구가 항상 0개**다.

```
[CLI-INIT] tools=[], mcp_servers=[{name=aimbase-server, status=pending}]
```

서버는 정상인데도 도구가 붙지 않는다. 실측으로 서버 측 결백을 확인했다:

```
POST http://api:8080/mcp  (initialize)
 → 200, protocolVersion=2025-11-25, Mcp-Session-Id 발급
 → tools/list = 110 개 정상 노출
```

### 2-1. 조용히 오답을 낸다 (운영 위험)

도구가 없어도 요청은 **성공(200)** 으로 끝난다. 모델이 도구를 못 쓰는 상태에서
"실행했다" 며 그럴듯한 값을 지어낸다. 실제로 관측된 사례:

```
질문: bash 도구로 `echo CR124_VERIFY_$(date +%s)` 를 실행하고 출력만 보여줘
응답: CR124_VERIFY_1753107626        ← 실행한 적 없는 날조값
실제 서버 시각(epoch): 1784672035    ← 약 1년 차이
```

`status=200` + 그럴듯한 본문이라 **호출 측이 실패를 인지할 수 없다.**
이것이 이 CR 의 severity 를 높이는 핵심이다.

---

## 3. 근거 — 통제 실험

같은 컨테이너·같은 계정·같은 mcp-config 에서 `--tools` 인자 **하나만** 바꿔 대조했다.
질문은 "`mcp__aimbase-server__bash` 로 `uname -n` 실행, 네이티브 Bash 도 시도" 로 고정.

| # | 인자 조합 | MCP 도구 | 네이티브 | 의도 부합 |
|---|-----------|---------|---------|----------|
| A | `--tools ""` **(현재 운영)** | ❌ 차단 | ❌ 차단 | ✗ MCP 까지 죽음 |
| B | `--tools` 제거 | ✅ 성공 | ✅ 열림 | ✗ 봉인 안 됨 |
| C | `--allowedTools mcp__aimbase-server__*` | ✅ 성공 | ✅ 열림 | ✗ 봉인 안 됨 |
| D | `--disallowedTools Bash/Edit/Write` | ✅ **성공** | ✅ **차단** | ✅ **의도대로** |
| E | `--tools "Read"` | ❌ 차단 | 제한됨 | ✗ MCP 까지 제한 |

D 의 실제 응답:

```
| mcp__aimbase-server__bash | 성공 — hostname: 39ba5e787e7d (exit 0) |
| 네이티브 Bash            | 차단 — 이 에이전트 환경에 내장되어 있지 않아 사용 불가 |
```

A/E 대비로 **`--tools` 는 값과 무관하게 MCP 도구까지 함께 제한**함이 확인된다.
반대로 `--disallowedTools` 는 built-in 만 골라 막고 MCP 는 살린다.

### 3-1. 날조가 아님을 확인한 방법

MCP 도구 실행 결과의 hostname 이 러너와 **다른** 컨테이너를 가리킨다.

```
mcp__aimbase-server__bash → 39ba5e787e7d   (api 컨테이너)
네이티브 Bash             → 9131bc7a6f2a   (러너 컨테이너)
```

모델이 지어낼 수 없는 값이므로 실제 원격 실행이 맞다.

---

## 4. 영향 범위

- AIMBASE 모드 + `sealNativeTools` 인 **모든 테넌트 러너**
- 운영 러너 4개 전부 해당 (`aimbase-agent`, `-wes`, `-workmap`, `-axopm`)
- NATIVE/HYBRID 모드는 `--tools` 를 안 붙이므로 무관

---

## 5. 수정 방향

`--tools ""` 를 `--disallowedTools` 기반 봉인으로 교체한다.

```java
} else if (toolMode == ToolMode.AIMBASE && sealNativeTools) {
    // CLI 2.1.x 의 --tools "" 는 built-in 뿐 아니라 MCP 도구까지 전부 끈다
    // (--help: "Use \"\" to disable all tools"). 네이티브만 봉인하려면
    // --disallowedTools 로 built-in 을 개별 차단해야 MCP 도구가 살아남는다.
    for (String t : NATIVE_TOOLS_TO_SEAL) {
        cmd.add("--disallowedTools");
        cmd.add(t);
    }
}
```

봉인 대상은 init 이벤트에서 관측된 built-in 목록을 기준으로 한다:

```
Task, Bash, CronCreate, CronDelete, CronList, DesignSync, Edit, EnterWorktree,
ExitWorktree, Monitor, NotebookEdit, PushNotification, Read, RemoteTrigger,
ReportFindings, ScheduleWakeup, SendMessage, Skill, TaskCreate, TaskGet,
TaskList, TaskOutput, TaskStop, TaskUpdate, ToolSearch, WebFetch, WebSearch,
Workflow, Write
```

### 5-1. 배포 후 발견 — `ToolSearch` 는 봉인하면 안 된다

`--disallowedTools` 로 바꿔 배포했더니 봉인은 정확히 걸렸지만(`tools=[Glob, Grep]`)
MCP 도구는 여전히 0개였다. 봉인 개수를 줄여가며 이분 탐색한 결과:

| 봉인 조합 | MCP 도구 |
|-----------|---------|
| 29개 전체 | ❌ 사라짐 |
| 3개 (Bash/Edit/Write) | ✅ 정상 |
| `Task` 만 | ✅ 정상 |
| `Read` 만 | ✅ 정상 |
| **`ToolSearch` 만** | ❌ **사라짐** |

**이 환경의 MCP 도구는 전부 deferred(지연 로딩)** 라서 모델이 `ToolSearch` 로
스키마를 불러와야 호출할 수 있다. `ToolSearch` 를 막으면 MCP 도구 110개 전체에
접근할 길이 사라져 `--tools ""` 와 똑같은 증상이 된다.

`ToolSearch` 를 제외한 28개 봉인으로 최종 확인:

```
| mcp__aimbase-server__bash | 성공 → 호스트명 4f73e458486c (api 컨테이너) |
| 네이티브 Bash             | 차단 → 도구 미등록으로 실행 불가            |
```

→ `DEFAULT_SEALED_NATIVE_TOOLS` 에서 `ToolSearch` 제외.
회귀 방어 테스트 `cr126_toolsearch_must_not_be_sealed` 추가.

### 결정 필요 사항

1. **봉인 범위** — built-in 전체를 막을지, `Bash/Edit/Write` 등 위험군만 막을지
2. **목록 관리 위치** — 상수 하드코딩 vs `global_config` 런타임 설정(CR-040 계열)
   CLI 버전이 오르면 built-in 목록이 바뀌므로 후자가 유리하다
3. **회귀 방어** — `--tools ""` 재도입을 막는 단위 테스트 추가

---

## 6. 함께 발견된 것 (별건, 이미 조치)

`deploy.sh` 의 `deploy_agents` 가 러너 이미지를 1개만 빌드했다.

```bash
docker compose build aimbase-agent && docker compose up -d --force-recreate $AGENT_SERVICES
```

compose 에 `image:` 지정이 없어 러너 4개가 서비스명 기반으로 **별도 이미지**
(`aimbase-aimbase-agent-wes` 등) 로 만들어지는데, 스크립트는 "모두 같은 이미지" 로
전제했다. 그 결과 `aimbase-agent` 만 새 jar 를 받고 나머지 3개는 옛 이미지로 재기동됐다.

실측된 방치 기간:

| 러너 | 배포 전 jar |
|------|------------|
| `aimbase-agent` | Jul 21 19:23 (최신) |
| `aimbase-agent-wes` | Jul 20 17:02 |
| `aimbase-agent-workmap` | Jul 6 23:11 |
| `aimbase-agent-axopm` | Jun 26 16:41 (약 1개월) |

`build aimbase-agent` → `build $AGENT_SERVICES` 로 수정하고 재배포하여 4개 모두
동일 jar(`Jul 21 19:23`) 로 정렬됨을 확인했다. **이 수정은 아직 커밋하지 않았다.**

---

## 7. 참고 — 오진 이력

같은 증상을 두고 두 번 잘못 짚었다. 재발 방지를 위해 남긴다.

| 오진 | 실제 |
|------|------|
| "Anthropic 5시간 한도 소진" | `rate_limit_event` 는 `status=allowed` 였다 |
| "aimbase MCP 엔드포인트 결함(404)" | `/mcp/sse` 404 는 CR-124 가 **의도한** 제거 결과 |
| "배포 후 도구 정상 복구" | 모델이 날조한 응답이었다(§2-1). 로그의 `tools=[]` 가 옳았다 |

**교훈: 모델 응답 본문을 도구 동작의 근거로 삼지 말 것.** 도구 없이도 그럴듯한
답이 나온다. `CLI-INIT` 로그, 또는 응답값이 실제 환경과 대조 가능한지
(hostname·타임스탬프) 로 검증해야 한다.
