# 원본 요구사항 — CR-067 EnhancedToolExecutor MCP Bridge 본문 노출

**작성일**: 2026-04-26
**원천**: CR-050 Phase 9 후속 진단 결과 + 사용자 발화

---

## 사용자 발화 (원문)

> "CR-050은 완료. 후속으로 발견된 이슈가 있다.
> EnhancedToolExecutor.execute(Map) default bridge 가 ToolResult.summary() 만 반환해서 MCP 서버 경유 시 모든 도구가 본문 대신 메타만 노출되는 문제. 메모리의 project_cr050_status.md 보고 후속 CR 진행해줘."

---

## 발견 경위 (CR-050 Phase 9 메모 발췌)

CR-050 Claude CLI → LLM 어댑터 승격 구현 후 API vs CLI 종합 벤치마킹 IT (`AdapterToolLoopComparisonIT`) 실측에서 다음 현상이 관찰됨:

| metric | API (anthropic) | CLI (anthropic-cli) |
|---|---:|---:|
| elapsed_ms | 146,385 | 265,405 |
| input_tokens | 16,627 | 25 |
| output_tokens | 555 | 11,906 |
| cache_creation_tokens | 71 | 46,979 |
| cache_read_tokens | 8,315 | 825,530 |
| cost_usd | $0.061 | $0.602 |

**응답 품질**:
- API: 6개 클래스 정확 식별 + 협력 흐름 도식화. 완성도 높은 분석.
- CLI: 도구 결과를 못 받아 작업 완수 못함. "환경 제약으로 도구 결과에서 실제 파일 내용을 얻을 수 없다"고 거부.

**MCP 채널 자체는 정상이었음**:
- ✅ MCP 채널 연결 정상 (`status=connected`, 14개 도구 노출)
- ✅ CLI native tool_use 정상 발행 (50회+, `[CLI-OBS]` 로그로 관찰)

**결정적 진단** (직접 stdio MCP 호출로 검증):

`tool-sdk-core/EnhancedToolExecutor.java:44-48`
```java
default String execute(Map<String, Object> input) {
    ToolResult result = execute(input, ToolContext.minimal(null, null));
    return result.summary();   // ← summary 만, output Map 의 content 무시
}
```

직접 stdio MCP 호출로 검증:
```
→ tools/call builtin_file_read absolute/path
← {"content":[{"type":"text","text":"/Users/.../ClaudeCliException.java (7줄)"}]}
```
파일 본문 대신 메타만.

---

## 본질

`AgentMcpServer.buildMcpServer` (tool-sdk-mcp) 가 도구 호출을 다음과 같이 디스패치:

```java
String result = tool.execute(args);   // legacy String 반환 메서드
result = McpResultTruncator.truncate(def.name(), result);
return new McpSchema.CallToolResult(result, false);
```

`tool` 이 `EnhancedToolExecutor` 인 경우 default bridge 로 떨어져:
1. `ToolContext.minimal(null, null)` 로 컨텍스트 빈 채로 새 메서드 호출
2. `ToolResult.summary()` 만 반환

→ `output` Map 의 본문 (예: 파일 내용, bash stdout, grep 결과) 가 누락되어 MCP 응답에 메타만 들어감.

→ Claude CLI 가 도구를 호출해도 모델은 항상 메타만 보게 됨 → 작업 완수 불가.

---

## 영향 범위

`grep -rn "implements EnhancedToolExecutor"` 결과:

**tool-sdk-core/builtin**: BashTool, FileReadTool, FileWriteTool, GlobTool, GrepTool, PatchApplyTool, SafeEditTool, StructuredSearchTool, WorkspaceSnapshotTool, PathInfoTool, DocumentSectionReadTool

**platform-core/builtin** (일부): WebSearchTool, HttpRequestTool, NotebookEditTool, LSPTool, BriefTool, CronListTool/CronDeleteTool, RemoteTriggerTool, SuggestBackgroundPRTool, TaskCreateTool/GetTool/ListTool/UpdateTool/StopTool, TeamCreateTool/DeleteTool, ToolSearchTool, EnterPlanModeTool, ReadToolResultTool

**총 30개+ EnhancedToolExecutor 구현체가 MCP 경유 시 본문 손실 영향권**

**MCP 외 경로 영향 없음**:
- ToolCallHandler / OrchestratorEngine 은 직접 `execute(Map, ToolContext)` 를 호출하므로 정상.
- bridge 가 호출되는 유일한 경로가 MCP 서버 (`AgentMcpServer.buildMcpServer:126`) + ToolController, CronScheduleManager 등 일부 (이들은 비MCP 경로지만 동일 패턴).

---

## 후속 영향

CR-050 어댑터 자체는 100% 정상 동작. 그러나 실용 가치는 본 CR 해소 전까지 제한적:
- CLI 어댑터 로 전환한 시나리오는 도구를 쓸 수 없음 (메타만 받음)
- BIZ-099 피처 플래그로 비활성 유지하면 영향 없으나, **Max 구독 정액제 활용** 이라는 CR-050 도입 동기가 무력화됨

---

## 수정 옵션 (CR-050 메모에서 인용)

- **옵션 A**: `EnhancedToolExecutor.execute(Map)` default 를 `output` Map 도 합쳐 직렬화
- **옵션 B**: `AgentMcpServer.buildMcpServer` 에서 `tool instanceof EnhancedToolExecutor` 분기 → `ToolResult.output` 직접 JSON 직렬화

## 결정 (2026-04-26 사용자 확정)

**옵션 A 정공 채택**.

**기각 사유 (옵션 B)**: MCP 한 곳만 본문을 살리고 default bridge 결함은 그대로 남는다. 동일 결함이 다른 호출처에 잠재:
- `ToolController:106` — Admin/UI 가 도구 직접 실행 시 본문 손실
- `CronScheduleManager:196` — 스케줄러 trigger 시 본문 손실
- `RemoteTriggerTool:115` — 원격 트리거 결과 본문 손실
- 향후 SDK 사용자가 `tool.execute(args)` 호출 시 동일 함정

옵션 B 로 가면 "MCP 는 본문, 그 외는 메타" 라는 비대칭 SDK 계약이 박혀 다음 사람이 또 같은 함정. 옵션 A 정공으로 bridge 자체의 의미를 "신 인터페이스를 String 으로 충실히 어댑트" 로 일관화한다.

**옵션 A 비용**: 비-MCP 호출처 4곳 회귀 점검 필요 (ToolController / CronScheduleManager / RemoteTriggerTool / HttpRequestToolTest 16+). "오래걸려도 정공" 결정으로 비용 수용.
