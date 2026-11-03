# IR-001 AXOPM 코칭 실행기 끊김 원인 규명과 복구

- 일자: 2026-10-01
- 요청: axopm-v5 (`docs/기록/요청_엠바스_코칭실행기끊김_IR-001.md`)
- 처리: axopm-v5-92 창에서 진행 (사용자 지시, IR-001 창은 열리지 않음)

## 결론

운영 서버의 aimbase 컨테이너가 2026-09-29 20:21 UTC 에 일제히 다시 켜졌을 때, CLI 러너 컨테이너 4개가 api 보다 먼저 떠서 등록에 실패했다. 러너는 등록 실패 뒤 다시 등록하지 않고 agentId=null 로 하트비트만 보내 400 을 받아 왔다. api 는 기존 등록을 STALE 로 내렸고, `cli-runner-axopm` 을 찾지 못한 코칭 호출이 모두 `LLM call failed` 로 끝났다.

러너 4개를 재기동해 다시 등록시켰고 코칭이 복구됐다.

## 근거

| 순번 | 시각 (UTC) | 내용 | 출처 |
|---|---|---|---|
| 1 | 09-28 00:04 | 마지막 정상 코칭 run (COMPLETED) | `GET /api/v1/workflows/runs` |
| 2 | 09-29 20:21:40 | aimbase-agent-axopm 컨테이너 시작 (RestartCount=0, StartedAt 갱신) | `docker inspect` |
| 3 | 09-29 20:22:33 | `Aimbase registration failed, agent continues without registration` | agent-axopm 로그 |
| 4 | 09-29 20:23:21 | api `Started PlatformApplication` (러너보다 48초 늦음) | api 로그 |
| 5 | 09-29 20:23:33 이후 | 러너가 `/api/v1/agents/null/heartbeat` 호출, `Heartbeat returned status 400` 반복 | api, agent 로그 |
| 6 | 09-29 20:25:21 | `Agent marked STALE: tenant=axopm_companyA, name=cli-runner-axopm` (workmap 도 같음) | api 로그 |
| 7 | 09-30 05:34 ~ 10-01 05:21 | 코칭 chat run 전부 FAILED, 약 10ms 만에 `LLM call failed` | runs API |

- 같은 시각에 `aimbase-agent`(bidding), `aimbase-agent-workmap`, `aimbase-agent-wes` 도 등록 실패 1건, 성공 0건이었다. 4개 러너가 모두 끊겨 있었다.
- 코드 원인: `sdk/tool-sdk-mcp/src/main/java/com/platform/mcp/agent/AgentLifecycle.java` 140~147행에서 등록 예외를 잡아 `agentId = null` 로 두고 끝낸다. 151~166행의 하트비트는 그 null 로 계속 호출하며 재등록 경로가 없다.

## 복구

- 2026-10-01 05:30 UTC, 사용자 승인 뒤 `docker restart` 로 러너 4개 재기동. 이미지와 설정은 그대로다.
- 4개 모두 `Agent registered successfully` (cli-runner-axopm, cli-runner-bidding, cli-runner-workmap, cli-runner-bp-wes).
- 확인: 직접 chat 호출 200 (4.6초), AXOPM 로컬 BE 의 성과 코칭 200 (77초, degraded=false), 해당 run COMPLETED.

## 남은 것

1. 재등록 누락 수정 (소스 변경, CR 대상): 등록 실패 시 재시도하거나, 하트비트가 agentId 없음 또는 400 을 받으면 다시 등록해야 한다. 이게 없으면 서버가 다시 켜질 때마다 같은 장애가 난다.
2. 커넥션 6c215520 의 status 칸은 복구 뒤에도 `disconnected` 로 남아 있다. 라우팅에는 쓰이지 않는 것으로 보이나 확인하지 않았다.
3. AXOPM 일반 커넥션 8051ea0e (anthropic) 의 400 은 Anthropic API 크레딧 소진이다. api 로그 2026-10-01 05:46:42 UTC `Your credit balance is too low to access the Anthropic API` (request_id req_011CfazKaQooywCDnExWiJ7A). AXOPM 지식 코치가 이 커넥션을 써서 실패한다. 러너 장애와는 별건이다.
   - 조치 (2026-10-01, 사용자 승인): 형식 답 전용 CLI 커넥션 `290a3c4c-a6b3-46e1-9a4f-966a059a9270` (Claude CLI (AXOPM 형식답), anthropic-cli, tool_mode=AIMBASE, agent_name=cli-runner-axopm, system_prompt_override 없음) 를 만들고 AXOPM 워크플로우 10개의 LLM_CALL connection_id 를 이것으로 바꿨다. AXOPM 쪽 기본값 변경은 axopm-v5 CR-127. 엠바스 코드 변경은 없다(CR-113 의 CLI 구조화 응답을 그대로 쓴다).
