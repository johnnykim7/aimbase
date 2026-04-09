# Aimbase Tool SDK 사용 가이드

> **v1.0.0** | 2026-04-10 | CR-041

소비앱이 Aimbase 도구를 로컬에서 사용하거나, 원격 Agent로 Aimbase 오케스트레이션에 참여하는 방법을 안내합니다.

---

## 1. 개요

### 아키텍처

```
[Aimbase 서버]                              [Agent (소비앱 로컬)]
  오케스트레이터 (두뇌)                         도구 실행 (팔다리)
  LLM 호출                                   aimbase-tool-sdk 사용
  프롬프트/정책/설정 (DB)                       로컬 파일 접근
  Agent 레지스트리 (주소 관리)                   MCP 서버로 대기
```

- **Agent는 도구 실행만** — LLM 호출 없음, 비용 절감
- **오케스트레이션은 Aimbase 서버** — LLM + 도구 루프 관리
- **통신은 MCP** — 온디맨드 연결 (상시 연결 없음)

### 사용 패턴

| 패턴 | 의존성 | 설명 |
|------|--------|------|
| ① LLM 대화만 | SDK 불필요 | Aimbase REST API 호출 ([API 가이드](aimbase-api-guide.md) 참조) |
| ② 로컬 도구만 | `sdk-core` | 파일 읽기/쓰기, Bash, Grep 등을 앱 내부에서 직접 사용 |
| ③ Aimbase 연동 Agent | `sdk-mcp` | MCP 서버로 도구 노출 + Aimbase 자동 등록 |

---

## 2. 의존성 추가

### Gradle (Kotlin DSL)

```kotlin
// ① 도구만 필요한 앱
implementation("com.platform:aimbase-tool-sdk-core:0.0.1-SNAPSHOT")

// ② MCP 서버까지 필요한 앱 (Agent 용도)
implementation("com.platform:aimbase-tool-sdk-mcp:0.0.1-SNAPSHOT")
```

### Gradle (Groovy)

```groovy
implementation 'com.platform:aimbase-tool-sdk-core:0.0.1-SNAPSHOT'
// 또는
implementation 'com.platform:aimbase-tool-sdk-mcp:0.0.1-SNAPSHOT'
```

> **참고**: `sdk-mcp`는 `sdk-core`를 transitively 포함합니다.

### 요구사항

- Java 21+
- `sdk-core`: 추가 프레임워크 불필요 (순수 Java)
- `sdk-mcp`: Spring Boot 3.4+ (MCP SSE 전송 계층)

---

## 3. 패턴 ②: 로컬 도구 직접 사용

Spring 없이도 사용 가능합니다.

### 3.1 SdkToolKit으로 한 번에 생성

```java
import com.platform.tool.SdkToolKit;
import com.platform.tool.ToolExecutor;

// 워크스페이스 경로 지정 (null이면 기본값 /data/workspaces)
SdkToolKit kit = new SdkToolKit("/home/user/project");

// 모든 SDK 도구 (14개) 한 번에 사용
List<ToolExecutor> tools = kit.getAllTools();

// 도구 목록 출력
tools.forEach(t -> System.out.println(t.getDefinition().name()));
```

### 3.2 개별 도구 사용

```java
import com.platform.tool.nativetool.FileReadTool;
import com.platform.tool.workspace.WorkspaceResolver;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.ToolContext;

// 워크스페이스 설정
WorkspaceResolver resolver = new WorkspaceResolver("/home/user/project");
WorkspacePolicyEngine policy = new WorkspacePolicyEngine(resolver);

// 도구 생성
FileReadTool readTool = new FileReadTool(resolver, policy);

// 실행
ToolContext ctx = ToolContext.minimal("tenant1", "session1");
var result = readTool.execute(
    Map.of("file_path", "/home/user/project/src/Main.java"),
    ctx
);

System.out.println(result.summary());
// → /home/user/project/src/Main.java (42줄)
```

### 3.3 포함된 도구 목록

| 도구 | 클래스 | 설명 |
|------|--------|------|
| `builtin_file_read` | FileReadTool | 파일 읽기 (오프셋/리밋, 바이너리 감지) |
| `builtin_file_write` | FileWriteTool | 파일 쓰기 (자동 mkdir, 10MB 제한) |
| `builtin_glob` | GlobTool | 패턴 매칭 파일 검색 |
| `builtin_grep` | GrepTool | 파일 내용 정규식 검색 |
| `builtin_safe_edit` | SafeEditTool | 구조화된 파일 편집 (insert/replace/delete) |
| `builtin_patch_apply` | PatchApplyTool | 유니파이드 diff 적용 |
| `builtin_path_info` | PathInfoTool | 파일 메타데이터 (크기, 타입, mtime) |
| `builtin_structured_search` | StructuredSearchTool | 멀티포맷 검색 (코드, 마크다운, JSON) |
| `builtin_document_section_read` | DocumentSectionReadTool | PDF/문서 섹션 추출 |
| `builtin_workspace_snapshot` | WorkspaceSnapshotTool | 디렉토리 트리 스냅샷 |
| `bash` | BashTool | 셸 명령 실행 (위험 명령 차단, 타임아웃) |
| `calculator` | CalculatorTool | 사칙연산 |
| `get_current_time` | GetCurrentTimeTool | 현재 시각 (ISO-8601) |
| `zip_extract` | ZipExtractTool | ZIP 압축 해제 (Zip Bomb 방어) |

---

## 4. 패턴 ③: Aimbase 연동 Agent 구축

### 4.1 최소 코드

```java
import com.platform.mcp.agent.AgentConfig;
import com.platform.mcp.agent.AgentLifecycle;

public class MyAgent {
    public static void main(String[] args) throws Exception {
        AgentConfig config = new AgentConfig(
            "my-agent",                    // 에이전트 이름
            "http://aimbase-server:8080",  // Aimbase 서버 주소
            "my-api-key",                  // API 키
            8190,                          // MCP 서버 포트
            "/workspace/path"              // 워크스페이스 루트
        );

        try (AgentLifecycle agent = new AgentLifecycle(config)) {
            agent.start();
            // MCP 서버 기동 → STUN 주소 탐색 → Aimbase 등록 → 하트비트 시작

            System.out.println("Agent running. Press Ctrl+C to stop.");
            Thread.currentThread().join();  // 대기
        }
        // close() 자동 호출 → 하트비트 중지 → Aimbase 해제 → MCP 서버 중지
    }
}
```

### 4.2 커스텀 도구 추가

SDK 기본 도구 외에 앱 고유 도구를 추가할 수 있습니다.

```java
import com.platform.tool.ToolExecutor;
import com.platform.tool.SdkToolKit;
import com.platform.tool.model.UnifiedToolDef;

// 커스텀 도구 구현
ToolExecutor myTool = new ToolExecutor() {
    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef("my_custom_tool",
            "프로젝트 빌드 상태를 확인합니다.",
            Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public String execute(Map<String, Object> input) {
        return "{\"status\": \"green\", \"coverage\": 87.5}";
    }
};

// SDK 기본 도구 + 커스텀 도구 합치기
List<ToolExecutor> allTools = new ArrayList<>(new SdkToolKit("/workspace").getAllTools());
allTools.add(myTool);

// 커스텀 도구 포함 Agent 생성
AgentLifecycle agent = new AgentLifecycle(config, allTools);
agent.start();
```

### 4.3 Agent 생명주기

```
Agent 기동
  → MCP 서버 시작 (지정 포트)
  → STUN 서버에 요청 → 공인 IP 주소 확인
  → Aimbase에 자동 등록: POST /api/v1/agents/register
  → 60초 간격 하트비트 시작

도구 실행 필요 시 (Aimbase가 주도)
  → Aimbase가 등록된 주소로 MCP 연결
  → 도구 호출 → 결과 반환
  → MCP 연결 종료

Agent 종료
  → 하트비트 중지
  → Aimbase에서 해제: DELETE /api/v1/agents/{id}
  → MCP 서버 중지
```

### 4.4 도구 실행 흐름 (전체)

```
1. 사용자 → Aimbase REST: "이 에러 분석해줘"
2. Aimbase 오케스트레이터 → LLM: "어떤 도구 쓸까?"
3. LLM: "builtin_file_read 써"
4. Aimbase → Agent 레지스트리에서 해당 도구 보유 Agent 조회
5. Aimbase → Agent MCP 연결 → tool_call 실행 → 결과 수신
6. Aimbase → LLM: "파일 내용 이거야, 다음은?"
7. 3~6 반복 (최대 도구 루프 횟수)
8. MCP 연결 종료
9. Aimbase → 사용자: "최종 분석 결과"
```

---

## 5. 설정 레퍼런스

### AgentConfig

| 필드 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `agentName` | String | (필수) | 에이전트 식별 이름 |
| `aimbaseUrl` | String | (필수) | Aimbase 서버 URL |
| `apiKey` | String | (필수) | 인증 API 키 |
| `mcpPort` | int | (필수) | MCP 서버 바인딩 포트 |
| `workspaceBase` | String | `/data/workspaces` | 워크스페이스 루트 경로 |
| `heartbeatIntervalMs` | long | `60000` | 하트비트 간격 (ms) |
| `stunServer` | String | `stun.l.google.com` | STUN 서버 호스트 |
| `stunPort` | int | `19302` | STUN 서버 포트 |

### 비즈니스 규칙

| 규칙 | 값 | 설명 |
|------|-----|------|
| BIZ-078 | 60초 | 에이전트 하트비트 간격 |
| BIZ-079 | 5분 | 무응답 시 STALE 처리 임계값 |
| BIZ-080 | 30초 | Aimbase 측 원격 도구 동기화 주기 |

---

## 6. Spring Boot 앱에서 사용

Spring Boot 앱에서는 `SdkToolBeanConfig`를 참고하여 Bean으로 등록할 수 있습니다.

```java
@Configuration
public class ToolConfig {

    @Bean
    public SdkToolKit sdkToolKit() {
        return new SdkToolKit("/my/workspace");
    }

    @Bean
    public AgentLifecycle agentLifecycle(SdkToolKit kit) {
        AgentConfig config = new AgentConfig(
            "my-spring-agent", "http://aimbase:8080", "api-key", 8190);

        return new AgentLifecycle(config, kit.getAllTools());
    }
}
```

```java
@Component
public class AgentStartupRunner implements ApplicationRunner {

    private final AgentLifecycle agent;

    public AgentStartupRunner(AgentLifecycle agent) {
        this.agent = agent;
    }

    @Override
    public void run(ApplicationArguments args) {
        agent.start();
        Runtime.getRuntime().addShutdownHook(new Thread(agent::close));
    }
}
```

---

## 7. 트러블슈팅

### STUN 탐색 실패

```
WARN  StunAddressResolver - STUN discovery failed, falling back to local address
```

- 방화벽이 UDP 19302 포트를 차단하는 환경
- 폴백: `InetAddress.getLocalHost()` 사용 (같은 네트워크에서는 문제 없음)
- 사내 STUN 서버가 있으면 `AgentConfig`의 `stunServer` 지정

### 에이전트 STALE 처리

```
WARN  AgentRegistryService - Agent marked STALE: id=..., lastHeartbeat=...
```

- 5분간 하트비트 미수신 → 자동 STALE 처리
- 하트비트 재개 시 자동 ACTIVE 복구
- 원인: 네트워크 단절, 프로세스 일시 중지

### MCP 연결 타임아웃

```
WARN  RemoteAgentToolExecutor - Remote tool execution failed: Connection timed out
```

- Agent MCP 서버가 중지 또는 네트워크 도달 불가
- 로컬 네트워크에서는 방화벽 확인
- TURN 서버 미구축 시 NAT 뒤의 Agent는 직접 연결 불가

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| v1.0.0 | 2026-04-10 | CR-041 초판 — sdk-core 14개 도구, sdk-mcp Agent 생명주기, Agent Registry API |
