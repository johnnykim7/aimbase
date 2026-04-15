# CLI 두뇌 + Aimbase 손발 패턴 정식화 (2026-04-14)

## 배경

Aimbase는 두 개의 에이전트 실행 경로를 가진다.

1. **API 경로** — Anthropic/OpenAI API 직접 호출 (종량제 과금)
2. **CLI 경로** — `ClaudeCodeTool` 경유 Claude Code CLI 서브프로세스 호출 (Claude Max 정액제)

두 경로의 비용 차이는 수십 배에 달하며, CLI 경로가 실질적 기본 운영 모드다. 그러나 현재 CLI 경로는 구조적 이중화 문제를 가진다:

- Aimbase는 이미 Bash / Read / Write / Edit / Grep / Glob / TodoWrite / Task / MCP 등 모든 핵심 도구를 네이티브로 보유(CR-037 / CR-038 / CR-041)하고 있다.
- Claude CLI도 동일한 네이티브 도구를 자체 보유한다.
- CLI 경로 실행 시 "같은 기능이 두 갈래"로 흐른다. 그 결과 Aimbase의 **PolicyEngine·감사 로그(BIZ-020)·WorkspaceResolver·테넌트 격리(BIZ-003)·비용/메트릭 집계**가 **CLI 쪽에서는 우회**된다.

CLI 도구와 Aimbase 도구가 동시에 살아있는 상태는 정책·관측성 관점에서 유지 불가능하다. 한쪽을 택해야 한다.

## 사용자 의도 (원문)

> "저희는 아시다시피 claude api 방식이 있고, claude cli tool 방식이 있습니다. claude cli tool 방식에서도 위와 같은 방식을 접목할 수 있나요? 즉, claude cli tool 내부에서 자기 툴 사용하지 말고 aimbase tool 사용하도록 강제화."

> "이 기능을 물어본 이유가 우리는 이미 툴이 다 있는데 중복해서 툴을 사용할까봐 그런 거에요. 즉, claude cli를 이용한 두뇌만 이용하고 싶은 거죠."

> "API를 사용하면 되는 거 아니냐라고 할 텐데, api와 claude cli의 종량제 요금제 차이 때문에 그래요."

> "비용에 대해서는 장단점이 있고 클로드 코드가 제안한 공식을 사용하는 거라 문제는 없다고 봅니다."

## 핵심 결정 사항

1. **CLI의 역할 축소 — 추론 엔진 전용**
   Claude Code CLI는 "고성능 추론기"로만 사용한다. 파일·셸·검색·편집·네트워크 호출 등 모든 실행 부작용은 Aimbase 도구 스택이 전담한다.

2. **공식 Claude Code 플래그 사용**
   `--mcp-config`, `--allowedTools`, `--disallowedTools` 세 플래그를 사용하여 네이티브 도구를 물리적으로 봉인한다. 이는 Anthropic이 공식 지원하는 확장 경로이므로 비공식 해킹이 아니다.

   ```
   claude \
     --mcp-config /tmp/aimbase-session-${sid}.json \
     --allowedTools "mcp__aimbase__*,TodoWrite,ExitPlanMode" \
     --disallowedTools "Bash,Read,Write,Edit,Grep,Glob,Task,WebFetch,WebSearch,NotebookEdit"
   ```

3. **Aimbase 도구는 MCP 서버로 노출**
   `aimbase-tool-sdk-mcp`(CR-041) 를 Claude CLI의 MCP 서버로 등록한다. CLI가 도구를 호출하면 JSON-RPC로 Aimbase MCP 서버에 도달하고, Aimbase의 평소 경로(**PolicyEngine → WorkspaceResolver → 감사 로그 → 실제 실행**)를 그대로 통과한다.

4. **API 경로와 CLI 경로의 도구 스택 완전 일원화**
   두 경로 모두 동일한 Aimbase 도구 구현, 동일한 정책, 동일한 감사 로그, 동일한 테넌트 격리를 공유한다. Skill/Policy/Workflow 정의는 한 번 만들면 두 경로에서 재사용된다.

5. **네이티브 유지 예외 2종**
   - `TodoWrite` — 사고 구조화용, 실행 부작용 없음
   - `ExitPlanMode` — Plan Mode 종료 신호
   - 근거: Claude CLI 내부 상태 관리 도구이며, Aimbase 외부에서 대체할 실익이 없다.

6. **Skill 단위 토구 브리지 모드**
   `Skill.metadata.toolBridge` 필드를 신설해 3가지 모드를 제공한다:
   - `aimbase-mcp-only` — 기본값, 정책·감사 엄격 적용이 필요한 운영 에이전트
   - `hybrid` — 실험·디버깅용, allowedTools에 Bash/Read 등 일부 네이티브 추가 허용
   - `native` — Claude CLI 순수 실행, 레거시 호환·벤치마크 전용

## 비용 및 Rate Limit 트레이드오프

### 이득
- 모델 호출 토큰(프롬프트/응답)이 Max 정액제 안에서 무료에 수렴. 이것이 비용의 대부분이다.
- Aimbase 도구 실행은 서버 자원만 소모, LLM 호출이 없다.
- 정책·감사·격리·관측성이 API 경로와 100% 동일한 코드 패스를 탄다.

### 주의
- Claude CLI가 MCP 응답 전문을 모델 컨텍스트에 다시 삽입하므로, 토큰 자체는 정액 안에서 과금되지 않지만 **Max 플랜의 시간당/하루 rate limit**(메시지·토큰 상한)을 소진한다.
- 긴 grep 결과·대용량 파일 read 등이 rate limit을 빠르게 소진할 수 있다.
- **완화책**: CR-031의 Tool Result 축약 로직을 CLI 경로의 MCP 응답 직전에 동일하게 적용한다. 긴 결과는 요약·페이지네이션·토큰 상한 절단을 미리 거쳐 MCP 응답으로 반환된다.
- MCP JSON-RPC 왕복 레이턴시가 네이티브 직접 호출 대비 소폭 증가한다. 대화형 세션에서는 체감 불가 수준.

사용자는 위 트레이드오프를 인지한 상태에서 본 패턴 채택을 결정했다.

## 기존 부품 현황 (재사용 가능)

| 부품 | 상태 | 출처 |
|---|---|---|
| `aimbase-tool-sdk-core` | 존재 (Bash / Read / Write / Edit / Grep / Glob 등 17개 도구) | CR-041 |
| `aimbase-tool-sdk-mcp` | 존재 (Aimbase 도구를 MCP 서버로 자동 노출) | CR-041 |
| `aimbase-agent` 독립 모듈 | 존재 | CR-042 |
| `ClaudeCodeTool` | 존재, Docker 구동 검증 완료 | CR-011 |
| `AimbaseAdminMcpConfig` | MCP 서버 구성 틀 존재 | 기존 |
| `PolicyEngine` (matchRules 기반 범용) | 존재, API 경로에서 이미 동작 | 기존 |
| `WorkspaceResolver` (테넌트별 경로 격리) | 존재, API 경로에서 이미 동작 | 기존 |
| 감사 로그(BIZ-020) | 존재, API 경로에서 이미 동작 | 기존 |
| `ToolResultCompactor` (CR-031 축약 로직) | 존재, API 경로에서 이미 동작 | CR-031 |

**결론**: 부품은 전부 있다. 이번 CR의 작업 내용은 "부품을 엮는 것"으로 한정된다. 신규 도구 구현이나 신규 엔진은 없다.

## 범용성 확인 메모

본 결정에 앞서 사용자는 "Aimbase 도구가 FlowGuard 시나리오 등록 같은 특정 용도에 종속된 건지, 범용인지"를 확인하였다. 조사 결과(대화 내 Phase 1 Explore 결과 기반):

- Aimbase 소스 어디에도 FlowGuard 전용 코드는 존재하지 않는다.
- Bash / Read / Write / Edit / Grep / Glob / TodoWrite / Task / MCP 도구, PolicyEngine, SUB_WORKFLOW, prompt_templates, global_config 전부 **범용 기능**이며, FlowGuard는 이 범용 기능들의 "사용 사례 중 하나"일 뿐이다.
- 따라서 본 CR의 적용 결과는 "FlowGuard 시나리오 등록 에이전트", "WMS 결함 수정 에이전트", "임의 프로젝트 자동화 에이전트" 등 **모든 Skill-기반 에이전트**에 동일하게 적용된다.

## 운영 효과

본 패턴이 정착되면 Aimbase는 사실상 **"Claude Max 구독을 백엔드로 쓰는 자체 오케스트레이터"** 가 된다.

| 경로 | 역할 | 비용 |
|---|---|---|
| Claude CLI | 추론만 (정액 안에서 무제한) | 0 |
| Aimbase 도구 | 실행 + 정책 + 감사 + 격리 + 관측성 | 서버 자원만 |
| API 경로 | 동일 도구 스택 재사용 (프리미엄 모델·긴 컨텍스트·고품질 모드 전용) | 종량제 |

운영자는 세션 단위로 "비용 절감 모드(CLI)" 와 "고품질 모드(API)" 를 선택하면 되고, 에이전트 구성(Skill/Policy/Workflow)은 한 번의 정의로 두 경로 모두에서 재사용된다.

## 참고

- 이 패턴은 업계에서 "LLM은 정액, 실행은 자체 샌드박스"로 알려진 구성이며, 비용 차이가 10~50배 나는 환경에서 사실상 유일한 현실적 선택지다.
- 본 요구사항은 CR-044 로 등록되며, 구현 착수 여부는 CR 등록 후 사용자 별도 승인으로 결정한다.
