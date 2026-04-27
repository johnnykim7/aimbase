package com.platform.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.orchestrator.stream.StreamEvent;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.platform.domain.master.AgentAccountEntity;
import com.platform.tenant.TenantContext;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Claude Code CLI를 래핑하는 내장 도구.
 *
 * Claude Code(자율 에이전트)를 Aimbase 워크플로우의 TOOL_CALL Step으로
 * 호출할 수 있게 한다. 파일 분석, 문서 생성, 코드 검토 등
 * 에이전트 수준의 작업을 워크플로우 DAG 안에서 실행.
 *
 * 인증 방식:
 * - 로컬/개발: `claude login`으로 OAuth 토큰 사용 (api-key 불필요)
 * - 서버/AWS: ANTHROPIC_API_KEY 환경변수 또는 claude-code.api-key 설정
 *
 * 설계 원칙:
 * - 오케스트레이션은 Aimbase가, 실행은 Claude Code가 (이중 루프 방지)
 * - 기본 읽기 전용 (Read, Grep, Glob만 허용)
 * - allowed_tools에 MCP 도구명을 추가하면 외부 서비스 연동 가능 (예: FlowGuard Step 등록)
 * - 로컬 경로를 working_directory로 지정하면 zip 없이 직접 파일 접근 (패스 모드)
 * - 후속 Step 연결 시 json_schema로 출력 구조 강제
 * - 프로세스 레벨 실패만 처리, 비즈니스 재시도는 워크플로우 엔진 위임
 */
@Component
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class ClaudeCodeTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 보안상 차단하는 CLI 옵션 (화이트리스트 검증) */
    private static final Set<String> BLOCKED_OPTIONS = Set.of(
            "--dangerously-skip-permissions",
            "--dangerously-skip-permissions=true",
            "--dangerously-skip-permissions-with-classifiers"
    );

    /**
     * CR-070 Phase A: 현재 스트리밍 컨텍스트의 StreamEvent 싱크.
     * ChatController.streamResponse() 같은 호출처가 Virtual Thread 시작 시 set, 종료 시 clear.
     * stream-json 출력 포맷에서 NDJSON 라인이 들어올 때마다 ToolUseStart/TextDelta 이벤트를 발행.
     * null이면 비스트리밍 경로 — 이벤트 발행 생략, 결과는 종전대로 통째로 반환.
     */
    private static final ThreadLocal<Consumer<StreamEvent>> STREAM_SINK = new ThreadLocal<>();

    public static void setStreamSink(Consumer<StreamEvent> sink) {
        STREAM_SINK.set(sink);
    }

    public static void clearStreamSink() {
        STREAM_SINK.remove();
    }

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "claude_code",
            "Claude Code CLI를 통한 고급 분석 수행. "
                    + "파일 읽기/검색, 코드 분석, 문서 생성, 데이터 정제 등 "
                    + "에이전트 수준의 자율 작업을 실행합니다.",
            Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<>(Map.ofEntries(
                            Map.entry("prompt", Map.of(
                                    "type", "string",
                                    "description", "Claude Code에 전달할 작업 지시 (자연어)"
                            )),
                            Map.entry("input_file", Map.of(
                                    "type", "string",
                                    "description", "stdin으로 전달할 입력 파일 경로 (선택, 대용량 문서 분석 시 사용)"
                            )),
                            Map.entry("output_format", Map.of(
                                    "type", "string",
                                    "enum", List.of("text", "json", "stream-json"),
                                    "description", "출력 포맷. 후속 Step 연결 시 json 권장. "
                                            + "stream-json=NDJSON 이벤트 스트림(장시간 작업 진행 추적 가능) (기본: text)"
                            )),
                            Map.entry("max_turns", Map.of(
                                    "type", "integer",
                                    "description", "에이전트 루프 최대 턴 수 (기본: 설정값)"
                            )),
                            Map.entry("allowed_tools", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "허용할 Claude Code 도구 목록. "
                                            + "레벨1=Read,Grep,Glob(분석) / 레벨2=+Write(생성) / 레벨3=+Bash(실행) "
                                            + "/ 레벨4=+mcp__*(MCP 도구). "
                                            + "MCP 서버에 등록된 도구명을 직접 지정 가능 (예: mcp__flowguard__create_step)"
                            )),
                            Map.entry("disallowed_tools", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "거부할 Claude Code 도구 목록. allowed_tools와 반대 방향 — "
                                            + "모든 도구를 허용하되 일부만 차단할 때 사용 (예: [\"Bash\", \"FileWrite\"])"
                            )),
                            Map.entry("json_schema", Map.of(
                                    "type", "object",
                                    "description", "출력 JSON 스키마 강제 (선택, 구조화 결과 필요 시)"
                            )),
                            Map.entry("working_directory", Map.of(
                                    "type", "string",
                                    "description", "작업 디렉토리 (기본: 시스템 설정값)"
                            )),
                            Map.entry("system_prompt", Map.of(
                                    "type", "string",
                                    "description", "기본 시스템 프롬프트 전체 교체 (선택). "
                                            + "append_system_prompt와 달리 Claude Code 기본 프롬프트를 완전히 대체함. "
                                            + "주의: 기본 안전 지시도 제거되므로 신중하게 사용"
                            )),
                            Map.entry("append_system_prompt", Map.of(
                                    "type", "string",
                                    "description", "시스템 프롬프트에 추가할 지시 (선택). 기본 프롬프트는 유지됨"
                            )),
                            Map.entry("model", Map.of(
                                    "type", "string",
                                    "enum", List.of("claude-sonnet-4-20250514",
                                            "claude-opus-4-20250514",
                                            "claude-haiku-4-20250414"),
                                    "description", "사용할 모델 (기본: CLI 기본값)"
                            )),
                            Map.entry("effort", Map.of(
                                    "type", "string",
                                    "enum", List.of("low", "medium", "high", "max"),
                                    "description", "추론 노력 수준 (기본: medium). max=Extended Thinking 최대 활성화"
                            )),
                            Map.entry("thinking", Map.of(
                                    "type", "string",
                                    "enum", List.of("adaptive", "enabled", "disabled"),
                                    "description", "Extended Thinking 모드. "
                                            + "adaptive=복잡도에 따라 자동 조절(권장) / "
                                            + "enabled=항상 활성화 / disabled=비활성화 (기본: adaptive)"
                            )),
                            Map.entry("max_thinking_tokens", Map.of(
                                    "type", "integer",
                                    "description", "Extended Thinking 토큰 예산 (선택). "
                                            + "thinking=enabled/adaptive 시 유효. 값이 클수록 깊은 추론 가능"
                            )),
                            Map.entry("permission_mode", Map.of(
                                    "type", "string",
                                    "enum", List.of("default", "plan", "bypassPermissions"),
                                    "description", "권한 모드. bypassPermissions는 워크플로우 명시 설정 시만 허용"
                            )),
                            Map.entry("session_persistence", Map.of(
                                    "type", "boolean",
                                    "description", "세션 영속 활성화 (기본: 설정값, 보통 true). "
                                            + "true면 프로젝트 단위 메모리 축적 + --continue/--resume 재개 가능"
                            )),
                            Map.entry("continue_mode", Map.of(
                                    "type", "string",
                                    "enum", List.of("new", "continue", "resume"),
                                    "description", "세션 모드. new=새 세션(기본), "
                                            + "continue=해당 워킹 디렉토리의 마지막 세션 이어가기, "
                                            + "resume=session_id로 특정 세션 재개"
                            )),
                            Map.entry("session_id", Map.of(
                                    "type", "string",
                                    "description", "재개할 세션 ID (continue_mode=resume 시 필수)"
                            )),
                            Map.entry("fork_session", Map.of(
                                    "type", "boolean",
                                    "description", "세션 분기 (continue/resume 시 유효). "
                                            + "true면 원본 세션은 보존하고 새 세션 ID로 분기 — 원본을 망치지 않고 탐색 가능"
                            )),
                            Map.entry("tools", Map.of(
                                    "type", "string",
                                    "description", "세션에서 사용 가능한 도구 셋 정의. "
                                            + "\"\"=모든 도구 비활성화 / \"default\"=기본 셋 / "
                                            + "\"Bash,Read,Edit\"=개별 지정. allowed_tools(권한 제어)와 다름"
                            )),
                            Map.entry("mcp_config", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "세션 단위로 주입할 MCP 서버 설정 목록. "
                                            + "각 항목은 JSON 파일 경로 또는 인라인 JSON 문자열. "
                                            + "예: [\"/etc/mcp/flowguard.json\", \"{\\\"mcpServers\\\":{...}}\"]"
                            )),
                            Map.entry("max_budget_usd", Map.of(
                                    "type", "number",
                                    "description", "세션 전체 USD 비용 상한선. 초과 시 실행 중단 (예: 0.5 = $0.50)"
                            )),
                            Map.entry("fallback_model", Map.of(
                                    "type", "string",
                                    "description", "주 모델 실패 시 사용할 폴백 모델 (예: claude-haiku-4-20250414)"
                            )),
                            Map.entry("task_budget", Map.of(
                                    "type", "integer",
                                    "description", "API 사이드 태스크 토큰 예산 (output_config.task_budget). "
                                            + "서버 사이드 비용 제어 — max_budget_usd와 함께 이중 가드로 사용 가능"
                            )),
                            Map.entry("cli_options", Map.of(
                                    "type", "object",
                                    "additionalProperties", Map.of("type", "string"),
                                    "description", "추가 CLI 옵션 맵 (예: {\"--verbose\": \"\", \"--color\": \"always\"}). "
                                            + "키는 옵션명(--포함), 값은 옵션 인수(플래그면 빈 문자열). "
                                            + "주의: --continue/--resume은 continue_mode 파라미터 사용"
                            )),
                            Map.entry("tool_bridge", Map.of(
                                    "type", "string",
                                    "enum", List.of("aimbase-mcp-only", "hybrid", "native"),
                                    "description", "CR-044 CLI 도구 브리지 모드. "
                                            + "aimbase-mcp-only=네이티브 도구 전면 봉인 + Aimbase MCP 도구만 허용 (정책·감사 완전 경유) / "
                                            + "hybrid=일부 네이티브(Bash/Read/Grep/Glob) 허용 + Aimbase MCP 병행 (실험·디버깅용) / "
                                            + "native=도구 제한 없음, 현재와 동일 동작 (기본값, 하위 호환)"
                            ))
                    )),
                    "required", List.of("prompt")
            )
    );

    private final ClaudeCodeToolConfig config;
    private final ClaudeCodeCircuitBreaker circuitBreaker;
    private final ErrorClassificationService errorClassificationService;
    private final ClaudeCodeNotificationService notificationService;
    private final AgentAccountPoolManager poolManager; // nullable — 풀 미설정 시 null

    public ClaudeCodeTool(ClaudeCodeToolConfig config,
                          ClaudeCodeCircuitBreaker circuitBreaker,
                          ErrorClassificationService errorClassificationService,
                          ClaudeCodeNotificationService notificationService,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          AgentAccountPoolManager poolManager) {
        this.config = config;
        this.circuitBreaker = circuitBreaker;
        this.errorClassificationService = errorClassificationService;
        this.notificationService = notificationService;
        this.poolManager = poolManager;
        // CR-044: MCP 설정 생성기에 jar 경로 주입
        AimbaseMcpConfigGenerator.configure(config.getMcpServerJar());
        log.info("ClaudeCodeTool 초기화: executable={}, timeout={}s, maxTurns={}, apiKey={}, pool={}",
                config.getExecutable(), config.getTimeoutSeconds(), config.getMaxTurns(),
                config.getApiKey() != null && !config.getApiKey().isBlank() ? "[설정됨]" : "[미설정-OAuth]",
                poolManager != null ? "[활성]" : "[미사용]");
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input) {
        String prompt = (String) input.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return toError("INVALID_INPUT", "prompt는 필수 파라미터입니다.");
        }

        // response_schema가 있으면 프롬프트에 JSON 출력 지시 주입
        Object responseSchema = input.get("response_schema");
        if (responseSchema != null) {
            try {
                String schemaJson = objectMapper.writeValueAsString(responseSchema);
                prompt = prompt + "\n\n[출력 규칙] 반드시 아래 JSON 스키마에 맞는 순수 JSON만 출력하라. "
                        + "마크다운, 코드펜스, 설명 텍스트 없이 JSON 객체만 응답하라.\n"
                        + "스키마: " + schemaJson;
                log.debug("response_schema를 프롬프트에 주입");
            } catch (JsonProcessingException e) {
                log.warn("response_schema 직렬화 실패: {}", e.getMessage());
            }
        }

        String inputFile = (String) input.getOrDefault("input_file", null);
        String outputFormat = (String) input.getOrDefault("output_format", "text");
        int maxTurns = input.containsKey("max_turns")
                ? ((Number) input.get("max_turns")).intValue()
                : config.getMaxTurns();
        List<String> allowedTools = input.containsKey("allowed_tools")
                ? (List<String>) input.get("allowed_tools")
                : (config.getDefaultAllowedTools() != null && !config.getDefaultAllowedTools().isBlank()
                        ? List.of(config.getDefaultAllowedTools().split(","))
                        : List.of());
        Map<String, Object> jsonSchema = (Map<String, Object>) input.getOrDefault("json_schema", null);
        String workDir = (String) input.getOrDefault("working_directory",
                config.getWorkingDirectory());
        String systemPrompt = (String) input.getOrDefault("system_prompt", null);
        String appendSystemPrompt = (String) input.getOrDefault("append_system_prompt",
                config.getDefaultSystemPrompt());
        String model = (String) input.getOrDefault("model", null);
        String effort = (String) input.getOrDefault("effort", null);
        String thinking = (String) input.getOrDefault("thinking", null);
        Integer maxThinkingTokens = input.containsKey("max_thinking_tokens")
                ? ((Number) input.get("max_thinking_tokens")).intValue()
                : null;
        String permissionMode = (String) input.getOrDefault("permission_mode", null);
        List<String> disallowedTools = input.containsKey("disallowed_tools")
                ? (List<String>) input.get("disallowed_tools")
                : List.of();
        boolean forkSession = Boolean.TRUE.equals(input.get("fork_session"));
        String toolsSpec = (String) input.getOrDefault("tools", null);
        List<String> mcpConfig = input.containsKey("mcp_config")
                ? new ArrayList<>((List<String>) input.get("mcp_config"))
                : new ArrayList<>();
        String toolBridge = (String) input.getOrDefault("tool_bridge", "native");

        // CR-044 + CR-069: tool_bridge 모드에 따라 allowed/disallowed 도구 목록 자동 구성.
        // Worker(LLM 어댑터) 와 동일한 잠금 정책 패턴 — ClaudeCliCommandBuilder.ToolMode 와 1:1 매핑:
        // aimbase-mcp-only → AIMBASE / hybrid → HYBRID / native → NATIVE
        if (!"native".equals(toolBridge)) {
            allowedTools = new ArrayList<>(allowedTools);
            disallowedTools = new ArrayList<>(disallowedTools);

            if ("aimbase-mcp-only".equals(toolBridge)) {
                // 네이티브 도구 전면 봉인 — Aimbase MCP 도구만 허용
                if (allowedTools.isEmpty()) {
                    allowedTools.addAll(List.of("mcp__aimbase__*", "TodoWrite", "ExitPlanMode"));
                }
                List<String> nativeToBlock = List.of(
                        "Bash", "Read", "Write", "Edit", "Grep", "Glob",
                        "Task", "WebFetch", "WebSearch", "NotebookEdit"
                );
                for (String tool : nativeToBlock) {
                    if (!disallowedTools.contains(tool)) {
                        disallowedTools.add(tool);
                    }
                }
                // Aimbase MCP 세션 설정 파일 자동 주입
                String mcpConfigPath = AimbaseMcpConfigGenerator.generateSessionConfig(
                        (String) input.getOrDefault("session_id", "default"));
                if (mcpConfigPath != null && !mcpConfig.contains(mcpConfigPath)) {
                    mcpConfig.add(mcpConfigPath);
                }
                // CR-069: stdio/자동화 환경 권한 게이트 봉인 (Worker 와 동일 패턴).
                // 사용자가 permission_mode 를 명시하지 않은 경우에만 default 적용.
                if (permissionMode == null || permissionMode.isBlank()) {
                    permissionMode = "bypassPermissions";
                }
                log.info("tool_bridge=aimbase-mcp-only: 네이티브 도구 봉인, Aimbase MCP 전용 모드 (permission=bypassPermissions)");

            } else if ("hybrid".equals(toolBridge)) {
                // 고위험 네이티브 도구만 차단 — Bash/Read/Grep/Glob 허용, 실행계 도구만 봉인
                List<String> hybridBlock = List.of("Write", "Edit", "Task", "WebFetch", "WebSearch", "NotebookEdit");
                for (String tool : hybridBlock) {
                    if (!disallowedTools.contains(tool)) {
                        disallowedTools.add(tool);
                    }
                }
                // Aimbase MCP 세션 설정 파일 자동 주입 (병행 사용)
                String mcpConfigPath = AimbaseMcpConfigGenerator.generateSessionConfig(
                        (String) input.getOrDefault("session_id", "default"));
                if (mcpConfigPath != null && !mcpConfig.contains(mcpConfigPath)) {
                    mcpConfig.add(mcpConfigPath);
                }
                log.info("tool_bridge=hybrid: 고위험 도구 봉인, Bash/Read/Grep/Glob + Aimbase MCP 병행 모드");
            }
        }

        Double maxBudgetUsd = input.containsKey("max_budget_usd")
                ? ((Number) input.get("max_budget_usd")).doubleValue()
                : null;
        String fallbackModel = (String) input.getOrDefault("fallback_model", null);
        Integer taskBudget = input.containsKey("task_budget")
                ? ((Number) input.get("task_budget")).intValue()
                : null;
        boolean sessionPersistence = input.containsKey("session_persistence")
                ? Boolean.TRUE.equals(input.get("session_persistence"))
                : config.isDefaultSessionPersistence();
        String continueMode = (String) input.getOrDefault("continue_mode", "new");
        String sessionId = (String) input.getOrDefault("session_id", null);

        // continue_mode=resume인데 session_id 없으면 에러
        if ("resume".equals(continueMode) && (sessionId == null || sessionId.isBlank())) {
            return toError("INVALID_INPUT", "continue_mode=resume 시 session_id는 필수입니다.");
        }

        Map<String, String> cliOptions = input.containsKey("cli_options")
                ? (Map<String, String>) input.get("cli_options")
                : Map.of();

        // 위험 옵션 검증
        String blockedOption = validateCliOptions(cliOptions);
        if (blockedOption != null) {
            return toError("BLOCKED_OPTION", "보안상 차단된 CLI 옵션입니다: " + blockedOption);
        }

        // 서킷 브레이커 체크
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            log.warn("ClaudeCodeTool 서킷 OPEN: 요청 차단 (openUntil={})", circuitBreaker.getOpenUntil());
            return toError("CIRCUIT_OPEN",
                    "서킷 브레이커가 열려 있습니다. %s까지 대기하거나 수동 리셋하세요."
                            .formatted(circuitBreaker.getOpenUntil()));
        }

        try {
            List<String> command = buildCommand(prompt, outputFormat, maxTurns,
                    allowedTools, disallowedTools, jsonSchema,
                    systemPrompt, appendSystemPrompt, model, effort, thinking, maxThinkingTokens,
                    permissionMode, sessionPersistence, continueMode, sessionId,
                    forkSession, toolsSpec, mcpConfig, maxBudgetUsd, fallbackModel, taskBudget,
                    cliOptions);

            log.info("ClaudeCodeTool 실행: max_turns={}, allowed_tools={}, output_format={}, model={}",
                    maxTurns, allowedTools, outputFormat, model != null ? model : "default");
            log.debug("ClaudeCodeTool 커맨드: {}", String.join(" ", command));

            // ── 풀 매니저 경로: 사이드카 HTTP 실행 ──
            if (poolManager != null) {
                String result = tryExecuteViaPool(input, command, workDir,
                        config.getTimeoutSeconds(), inputFile, outputFormat);
                if (result != null) {
                    return result;
                }
                // 풀에서 가용 계정 없으면 로컬 ProcessBuilder 폴백
            }

            // ── 로컬 ProcessBuilder 경로 (기존) ──
            ProcessBuilder pb = new ProcessBuilder(command);

            // 중첩 세션 방지: 부모 프로세스가 Claude Code 세션이면 CLAUDECODE 환경변수 제거
            pb.environment().remove("CLAUDECODE");

            // API 키가 설정되어 있으면 --bare 모드로 전환 (CLI가 bare에서만 API Key 인증)
            String apiKey = config.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                pb.environment().put("ANTHROPIC_API_KEY", apiKey);
                if (!command.contains("--bare")) {
                    command = new ArrayList<>(command);
                    command.add(1, "--bare");
                }
            }

            if (workDir != null && !workDir.isBlank()) {
                File dir = new File(workDir);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                } else {
                    log.warn("작업 디렉토리 없음, 기본 디렉토리 사용: {}", workDir);
                }
            }
            pb.redirectErrorStream(false);

            if (inputFile != null && !inputFile.isBlank()) {
                File inFile = new File(inputFile);
                if (!inFile.exists()) {
                    return toError("FILE_NOT_FOUND", "입력 파일을 찾을 수 없습니다: " + inputFile);
                }
                pb.redirectInput(inFile);
            } else {
                // stdin이 없으면 /dev/null로 리다이렉트 (CLI의 stdin 대기 경고 방지)
                pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            }

            Process process = pb.start();

            // CR-070 Phase A: stream-json 포맷이고 STREAM_SINK 가 등록된 경우 라인 단위로 NDJSON 이벤트를 emit.
            // ThreadLocal 은 호출 스레드 (Virtual Thread) 에서 평가하고, supplyAsync 별도 스레드에는
            // 캡처된 sink 를 직접 전달한다.
            Consumer<StreamEvent> capturedSink =
                    "stream-json".equals(outputFormat) ? STREAM_SINK.get() : null;
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> capturedSink != null
                            ? readStreamWithSink(process.getInputStream(), capturedSink)
                            : readStream(process.getInputStream())
            );
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream())
            );

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("ClaudeCodeTool 타임아웃: {}초 초과", config.getTimeoutSeconds());
                handleFailure("TIMEOUT", "실행 타임아웃");
                return toError("TIMEOUT",
                        "Claude Code 실행이 %d초 제한을 초과했습니다.".formatted(config.getTimeoutSeconds()));
            }

            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.error("ClaudeCodeTool 실패: exit_code={}, stderr={}", exitCode, stderr);
                String errorOutput = stderr.isBlank() ? stdout : stderr;
                handleFailure("EXIT_" + exitCode, errorOutput);
                return toError("EXIT_" + exitCode, errorOutput);
            }

            // 성공 → 서킷 브레이커 리셋 + 복구 알림
            if (circuitBreaker != null) {
                boolean wasNotClosed = circuitBreaker.getState() != ClaudeCodeCircuitBreaker.State.CLOSED;
                circuitBreaker.recordSuccess();
                if (wasNotClosed && notificationService != null) {
                    notificationService.notifyCircuitRecovered();
                }
            }

            log.info("ClaudeCodeTool 완료: exit_code=0, output_length={}", stdout.length());

            // 포맷에 따라 결과 추출
            if ("json".equals(outputFormat)) {
                return extractResultFromJson(stdout);
            }
            if ("stream-json".equals(outputFormat)) {
                return extractResultFromStreamJson(stdout);
            }
            return stdout;

        } catch (Exception e) {
            log.error("ClaudeCodeTool 예외: {}", e.getMessage(), e);
            handleFailure("EXCEPTION", e.getMessage());
            return toError("EXCEPTION", e.getMessage());
        }
    }

    /**
     * CLI 커맨드를 빌드한다.
     * 우선순위: named params → cli_options (중복 시 named params 우선)
     * -p (print mode)는 항상 강제, --add-dir는 working-directory 설정 시 자동 추가.
     */
    @SuppressWarnings("java:S107") // 파라미터 수가 많지만 CLI 옵션 1:1 매핑으로 분리 불가
    private List<String> buildCommand(String prompt, String outputFormat, int maxTurns,
                                       List<String> allowedTools, List<String> disallowedTools,
                                       Map<String, Object> jsonSchema,
                                       String systemPrompt, String appendSystemPrompt,
                                       String model, String effort,
                                       String thinking, Integer maxThinkingTokens,
                                       String permissionMode, boolean sessionPersistence,
                                       String continueMode, String sessionId,
                                       boolean forkSession, String toolsSpec,
                                       List<String> mcpConfig,
                                       Double maxBudgetUsd, String fallbackModel, Integer taskBudget,
                                       Map<String, String> cliOptions) {
        // named params가 이미 처리하는 옵션 — cli_options에서 중복 방지
        Set<String> handledOptions = Set.of(
                "-p", "--output-format", "--max-turns",
                "--allowedTools", "--allowed-tools",
                "--disallowedTools", "--disallowed-tools",
                "--json-schema", "--system-prompt",
                "--append-system-prompt", "--model", "--fallback-model",
                "--add-dir", "--no-session-persistence", "--permission-mode",
                "--reasoning-effort", "--thinking", "--max-thinking-tokens",
                "--continue", "--resume", "--fork-session",
                "--tools", "--mcp-config", "--strict-mcp-config",
                "--max-budget-usd", "--task-budget"
        );

        List<String> cmd = new ArrayList<>();
        cmd.add(config.getExecutable());

        // continue_mode에 따른 세션 재개 옵션 (프롬프트 앞에 위치)
        if ("continue".equals(continueMode)) {
            cmd.add("--continue");
        } else if ("resume".equals(continueMode) && sessionId != null && !sessionId.isBlank()) {
            cmd.add("--resume");
            cmd.add(sessionId);
        }

        // 세션 분기: resume/continue 시 원본 보존하고 새 세션 ID로 분기
        if (forkSession) {
            cmd.add("--fork-session");
        }

        cmd.add("-p");
        cmd.add(prompt);
        cmd.add("--output-format");
        cmd.add(outputFormat);
        cmd.add("--max-turns");
        cmd.add(String.valueOf(maxTurns));

        // 세션 영속 제어: 기본 true (프로젝트 메모리 축적), 명시적으로 비활성화 가능
        if (!sessionPersistence) {
            cmd.add("--no-session-persistence");
        }

        for (String tool : allowedTools) {
            String trimmed = tool.trim();
            if (!trimmed.isEmpty()) {
                cmd.add("--allowedTools");
                cmd.add(trimmed);
            }
        }

        for (String tool : disallowedTools) {
            String trimmed = tool.trim();
            if (!trimmed.isEmpty()) {
                cmd.add("--disallowedTools");
                cmd.add(trimmed);
            }
        }

        // 가용 도구 셋 정의 (allowedTools와 다른 개념 — 사용 가능한 도구 셋 자체를 제한)
        if (toolsSpec != null && !toolsSpec.isBlank()) {
            cmd.add("--tools");
            cmd.add(toolsSpec);
        }

        if (jsonSchema != null && !jsonSchema.isEmpty()) {
            try {
                cmd.add("--json-schema");
                cmd.add(objectMapper.writeValueAsString(jsonSchema));
            } catch (JsonProcessingException e) {
                log.warn("JSON 스키마 직렬화 실패, 스키마 무시: {}", e.getMessage());
            }
        }

        // 시스템 프롬프트 전체 교체 (append보다 먼저 처리)
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(systemPrompt);
        }

        if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(appendSystemPrompt);
        }

        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        if (fallbackModel != null && !fallbackModel.isBlank()) {
            cmd.add("--fallback-model");
            cmd.add(fallbackModel);
        }

        if (effort != null && !effort.isBlank()) {
            cmd.add("--reasoning-effort");
            cmd.add(effort);
        }

        if (thinking != null && !thinking.isBlank()) {
            cmd.add("--thinking");
            cmd.add(thinking);
        }

        if (maxThinkingTokens != null) {
            cmd.add("--max-thinking-tokens");
            cmd.add(String.valueOf(maxThinkingTokens));
        }

        if (permissionMode != null && !permissionMode.isBlank()) {
            cmd.add("--permission-mode");
            cmd.add(permissionMode);
        }

        if (maxBudgetUsd != null) {
            cmd.add("--max-budget-usd");
            cmd.add(String.valueOf(maxBudgetUsd));
        }

        if (taskBudget != null) {
            cmd.add("--task-budget");
            cmd.add(String.valueOf(taskBudget));
        }

        // MCP 서버 설정 주입 (세션 단위)
        // CR-069: --mcp-config 가 하나라도 있으면 --strict-mcp-config 동반 주입 — 사용자 글로벌
        // ~/.claude/.mcp.json 오염 방지 (Worker 와 동일 잠금 정책).
        boolean hasMcpConfig = false;
        for (String mcpEntry : mcpConfig) {
            if (mcpEntry != null && !mcpEntry.isBlank()) {
                if (!hasMcpConfig) {
                    cmd.add("--strict-mcp-config");
                    hasMcpConfig = true;
                }
                cmd.add("--mcp-config");
                cmd.add(mcpEntry);
            }
        }

        // cli_options 맵에서 추가 옵션 병합 (named params와 중복되지 않는 것만)
        if (cliOptions != null) {
            for (Map.Entry<String, String> entry : cliOptions.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) continue;
                if (handledOptions.contains(key)) {
                    log.debug("cli_options 중복 옵션 무시 (named param 우선): {}", key);
                    continue;
                }
                cmd.add(key);
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    cmd.add(value);
                }
            }
        }

        // 작업 디렉토리를 CLI에 전달 — VS Code 플러그인과 동일하게 해당 디렉토리 내 파일 접근 허용
        String workDir = config.getWorkingDirectory();
        if (workDir != null && !workDir.isBlank()) {
            cmd.add("--add-dir");
            cmd.add(workDir);
        }

        return cmd;
    }

    /** 실패 시 에러 분류 + 서킷 브레이커 기록 + 알림 */
    private void handleFailure(String errorCode, String errorOutput) {
        if (errorClassificationService != null) {
            ErrorClassification classification = errorClassificationService.classify(errorOutput);
            log.info("에러 분류: type={}, action={}, pattern={}",
                    classification.errorType(), classification.action(), classification.pattern());

            if ("CIRCUIT_BREAKER".equals(classification.action()) && circuitBreaker != null) {
                circuitBreaker.recordFailure();
                // 서킷이 OPEN으로 전환되었으면 알림
                if (circuitBreaker.getState() == ClaudeCodeCircuitBreaker.State.OPEN
                        && notificationService != null) {
                    notificationService.notifyCircuitOpen(circuitBreaker.getConsecutiveFailures());
                }
            }

            // NOTIFY 액션: 즉시 알림 (AUTH_EXPIRED, RATE_LIMIT 등)
            if ("NOTIFY".equals(classification.action()) && notificationService != null) {
                notificationService.notifyError(classification);
            }
            // RETRY 액션은 워크플로우 엔진에서 처리 (여기서는 분류만)
        } else if (circuitBreaker != null) {
            circuitBreaker.recordFailure();
        }
    }

    /** cli_options에 차단 옵션이 있으면 해당 옵션명 반환, 없으면 null */
    private String validateCliOptions(Map<String, String> cliOptions) {
        if (cliOptions == null) return null;
        for (String key : cliOptions.keySet()) {
            if (key != null && BLOCKED_OPTIONS.contains(key.toLowerCase())) {
                return key;
            }
        }
        return null;
    }

    /**
     * Claude Code의 --output-format json 결과에서 실제 응답을 추출.
     *
     * --json-schema 사용 시: structured_output 필드에 스키마 준수 JSON 객체 반환
     * 그 외: result 필드의 텍스트 반환
     */
    @SuppressWarnings("unchecked")
    private String extractResultFromJson(String jsonOutput) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonOutput, Map.class);

            // structured_output 우선 (--json-schema 사용 시 스키마 준수 JSON)
            if (parsed.containsKey("structured_output") && parsed.get("structured_output") != null) {
                Object structured = parsed.get("structured_output");
                log.debug("structured_output 필드 발견, JSON 반환");
                return objectMapper.writeValueAsString(structured);
            }

            // result 필드 fallback
            if (parsed.containsKey("result")) {
                Object result = parsed.get("result");
                if (result instanceof String resultStr) {
                    // 코드펜스 제거 후 JSON 파싱 시도
                    String stripped = stripCodeFence(resultStr);
                    try {
                        Object jsonObj = objectMapper.readValue(stripped, Object.class);
                        return objectMapper.writeValueAsString(jsonObj);
                    } catch (Exception ignore) {
                        return stripped;
                    }
                }
                return objectMapper.writeValueAsString(result);
            }
            return jsonOutput;
        } catch (Exception e) {
            log.debug("JSON 결과 추출 실패, 원본 반환: {}", e.getMessage());
            return jsonOutput;
        }
    }

    /**
     * stream-json 출력(NDJSON)에서 최종 결과를 추출.
     *
     * openclaude의 stream-json 형식은 각 줄이 독립적인 JSON 이벤트:
     *   {"type":"system","subtype":"init",...}
     *   {"type":"assistant","message":{...}}
     *   {"type":"result","subtype":"success","result":"...","session_id":"...","total_cost_usd":0.001}
     *
     * result 타입 이벤트의 result 필드를 추출해서 반환.
     * result 이벤트가 없으면 마지막 assistant 메시지 텍스트 반환.
     * 둘 다 없으면 원본 그대로 반환.
     */
    @SuppressWarnings("unchecked")
    private String extractResultFromStreamJson(String ndjsonOutput) {
        if (ndjsonOutput == null || ndjsonOutput.isBlank()) return ndjsonOutput;

        String resultText = null;
        String lastAssistantText = null;

        for (String line : ndjsonOutput.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                Map<String, Object> event = objectMapper.readValue(trimmed, Map.class);
                String type = (String) event.get("type");

                if ("result".equals(type)) {
                    Object result = event.get("result");
                    if (result instanceof String s) {
                        resultText = stripCodeFence(s);
                    } else if (result != null) {
                        resultText = objectMapper.writeValueAsString(result);
                    }
                    // result 이벤트를 찾으면 즉시 반환 (마지막에 오는 게 보장됨)
                    if (resultText != null) {
                        // JSON 파싱 시도 (structured output인 경우)
                        try {
                            Object jsonObj = objectMapper.readValue(resultText, Object.class);
                            return objectMapper.writeValueAsString(jsonObj);
                        } catch (Exception ignore) {
                            return resultText;
                        }
                    }
                } else if ("assistant".equals(type)) {
                    // 폴백용: 마지막 assistant 메시지 텍스트 보관
                    Object message = event.get("message");
                    if (message instanceof Map<?, ?> msgMap) {
                        Object content = msgMap.get("content");
                        if (content instanceof List<?> contentList && !contentList.isEmpty()) {
                            Object firstBlock = contentList.get(0);
                            if (firstBlock instanceof Map<?, ?> block && "text".equals(block.get("type"))) {
                                lastAssistantText = (String) block.get("text");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.trace("stream-json 라인 파싱 스킵: {}", trimmed);
            }
        }

        // result 이벤트 없으면 마지막 assistant 텍스트 폴백
        if (lastAssistantText != null) {
            log.debug("stream-json: result 이벤트 없음, 마지막 assistant 텍스트 반환");
            return lastAssistantText;
        }

        log.debug("stream-json: 결과 이벤트 없음, 원본 반환");
        return ndjsonOutput;
    }

    private String readStream(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            log.error("스트림 읽기 실패: {}", e.getMessage());
            return "";
        }
    }

    /**
     * CR-070 Phase A: stream-json 포맷 stdout 라인 단위 처리 + sink 직접 emit.
     *
     * Claude Code CLI의 stream-json 이벤트 → Aimbase StreamEvent 매핑:
     * - {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}  → TextDelta
     * - {"type":"assistant","message":{"content":[{"type":"tool_use","id":"...","name":"...","input":{...}}]}} → ToolUseStart
     * - {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"...","content":"..."}]}} → ToolResultEvent
     * - {"type":"result", ...} → emit 안 함 (extractResultFromStreamJson에서 최종 추출)
     *
     * 누적 문자열은 그대로 반환 (extractResultFromStreamJson 호환 유지).
     * sink 는 호출 스레드에서 캡처해서 전달 — supplyAsync 별도 스레드에서도 안전하게 emit 가능.
     */
    @SuppressWarnings("unchecked")
    private String readStreamWithSink(InputStream inputStream, Consumer<StreamEvent> sink) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);

                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    Map<String, Object> event = objectMapper.readValue(trimmed, Map.class);
                    emitFromStreamJsonLine(event, sink);
                } catch (Exception e) {
                    log.trace("stream-json 라인 파싱 스킵 (sink): {}", trimmed);
                }
            }
        } catch (IOException e) {
            log.error("스트림 읽기 실패 (sink): {}", e.getMessage());
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void emitFromStreamJsonLine(Map<String, Object> event, Consumer<StreamEvent> sink) {
        String type = (String) event.get("type");
        if (type == null || sink == null) return;

        if ("assistant".equals(type)) {
            Object message = event.get("message");
            if (!(message instanceof Map<?, ?> msgMap)) return;
            Object content = msgMap.get("content");
            if (!(content instanceof List<?> blocks)) return;
            for (Object block : blocks) {
                if (!(block instanceof Map<?, ?> blockMap)) continue;
                String blockType = (String) blockMap.get("type");
                if ("text".equals(blockType)) {
                    Object text = blockMap.get("text");
                    if (text instanceof String s && !s.isEmpty()) {
                        emitTo(sink, new StreamEvent.TextDelta(s));
                    }
                } else if ("tool_use".equals(blockType)) {
                    String id = (String) blockMap.get("id");
                    String name = (String) blockMap.get("name");
                    Object input = blockMap.get("input");
                    Map<String, Object> inputMap = (input instanceof Map<?, ?> mp)
                            ? (Map<String, Object>) mp : Map.of();
                    if (name != null) {
                        emitTo(sink, new StreamEvent.ToolUseStart(id != null ? id : "", name, inputMap));
                    }
                }
            }
        } else if ("user".equals(type)) {
            Object message = event.get("message");
            if (!(message instanceof Map<?, ?> msgMap)) return;
            Object content = msgMap.get("content");
            if (!(content instanceof List<?> blocks)) return;
            for (Object block : blocks) {
                if (!(block instanceof Map<?, ?> blockMap)) continue;
                if (!"tool_result".equals(blockMap.get("type"))) continue;
                String toolUseId = (String) blockMap.get("tool_use_id");
                Object resultContent = blockMap.get("content");
                String output = resultContent == null ? "" : resultContent.toString();
                Object isError = blockMap.get("is_error");
                boolean error = Boolean.TRUE.equals(isError);
                emitTo(sink, new StreamEvent.ToolResultEvent(toolUseId != null ? toolUseId : "", output, error));
            }
        }
        // "result", "system" 등은 emit 생략 (최종 결과는 extractResultFromStreamJson)
    }

    private static void emitTo(Consumer<StreamEvent> sink, StreamEvent event) {
        try {
            sink.accept(event);
        } catch (Exception e) {
            log.warn("ClaudeCodeTool stream emit 실패: {}", event.getClass().getSimpleName(), e);
        }
    }

    /**
     * 풀 매니저를 통한 로컬 실행 시도 (CLAUDE_CONFIG_DIR로 계정 격리).
     * 가용 계정이 있으면 실행 결과 반환, 없으면 null (기본 로컬 경로 폴백).
     *
     * CR-043: 호출중 자동 재시도 — 특정 계정 실행이 재시도 가능한 에러로 실패하면
     * 해당 계정을 circuit에 실패 기록하고 다른 가용 계정으로 최대 config.maxRetry 회 재시도.
     * 비재시도 에러(사용자 오류 등)는 즉시 반환.
     */
    private String tryExecuteViaPool(Map<String, Object> input, List<String> command,
                                      String workDir, int timeoutSeconds,
                                      String inputFile, String outputFormat) {
        String explicitAccountId = (String) input.get("_agent_account_id");
        String tenantId = TenantContext.hasTenant() ? TenantContext.getTenantId() : null;

        int maxRetry = config.getMaxRetry();
        int totalAttempts = 1 + Math.max(0, maxRetry);
        long backoffMs = config.getRetryBackoffMs();

        AgentAccountPoolManager.ExecutionResult lastResult = null;
        String lastAccountId = null;

        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            AgentAccountEntity account;
            if (explicitAccountId != null) {
                // 명시 계정은 재시도하지 않음 — 1회만 시도하고 반환
                if (attempt > 0) break;
                account = poolManager.getAccount(explicitAccountId);
                if (account == null) {
                    log.warn("명시된 계정 없음: {}, 로컬 폴백", explicitAccountId);
                    return null;
                }
            } else {
                account = poolManager.resolveAccount("claude_code", tenantId, null);
                if (account == null) {
                    if (attempt == 0) {
                        return null; // 풀 자체가 비어있음 → 로컬 폴백
                    }
                    log.warn("재시도 중 가용 계정 소진 (attempt={}/{})", attempt, totalAttempts);
                    break;
                }
            }

            if (!poolManager.getCircuitBreaker(account.getId()).allowRequest()) {
                log.warn("[{}] 서킷 OPEN, 다음 후보 시도 (attempt={}/{})",
                        account.getId(), attempt + 1, totalAttempts);
                continue;
            }

            if (attempt > 0 && backoffMs > 0) {
                long delay = backoffMs * (1L << (attempt - 1)); // 500, 1500, ... 실제로는 500·(2^(n-1))
                log.info("재시도 backoff {}ms (attempt={}/{})", delay, attempt + 1, totalAttempts);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log.info("[{}] 풀 실행 시도 (attempt={}/{})", account.getId(), attempt + 1, totalAttempts);
            var result = poolManager.executeLocal(account, command, workDir, timeoutSeconds, inputFile);
            lastResult = result;
            lastAccountId = account.getId();

            if (result.exitCode() == 0) {
                poolManager.recordSuccess(account.getId());
                log.info("[{}] 실행 완료 (attempt={}): output_length={}",
                        account.getId(), attempt + 1, result.stdout().length());
                if ("json".equals(outputFormat)) {
                    return extractResultFromJson(result.stdout());
                }
                if ("stream-json".equals(outputFormat)) {
                    return extractResultFromStreamJson(result.stdout());
                }
                return result.stdout();
            }

            // 실패 — 재시도 여부 판별
            String errOut = result.stderr().isBlank() ? result.stdout() : result.stderr();
            boolean retryable = isRetryableFailure(result.exitCode(), errOut);
            log.error("[{}] 실행 실패 (attempt={}/{}, retryable={}): exit={}, stderr={}",
                    account.getId(), attempt + 1, totalAttempts, retryable, result.exitCode(), errOut);
            poolManager.recordFailure(account.getId());
            handleFailure("EXIT_" + result.exitCode(), errOut);

            if (!retryable) {
                // 사용자 오류 등 → 즉시 반환
                return toError("EXIT_" + result.exitCode(), errOut);
            }
            // 재시도 루프 계속 — 다음 iter에서 resolveAccount 재호출하면
            // 방금 실패 기록된 계정은 GenericCircuitBreaker에 의해 자동 스킵됨
        }

        // 모든 재시도 소진 — 마지막 실패 반환 (보안상 계정 ID는 감추고 audit log에만 기록)
        if (lastResult != null) {
            log.warn("CR-043 재시도 {}회 모두 실패, 최종 반환 (last_account={})",
                    totalAttempts, lastAccountId);
            String errOut = lastResult.stderr().isBlank() ? lastResult.stdout() : lastResult.stderr();
            return toError("EXIT_" + lastResult.exitCode(),
                    "다중 계정 시도 후 실패: " + errOut);
        }
        // 한 번도 실행되지 못함 (모든 계정 서킷 OPEN 등) → 로컬 폴백
        return null;
    }

    /**
     * CR-043: 실패가 "다른 계정으로 재시도할 가치가 있는지" 판별.
     * 재시도 대상:
     *  - 인증 실패 (401/403, "unauthorized", "token expired", "authentication")
     *  - Rate Limit (429, "rate limit", "too many requests", "quota")
     *  - 서버 일시 장애 (5xx, "internal server error", "bad gateway", "service unavailable")
     * 비재시도 (사용자 오류 등):
     *  - 프롬프트/입력 오류, 도구 거부, 파일 없음 등
     */
    static boolean isRetryableFailure(int exitCode, String errorOutput) {
        if (errorOutput == null) return false;
        String lower = errorOutput.toLowerCase();

        // HTTP 코드 문자열
        if (lower.contains("401") || lower.contains("403") || lower.contains("429")) return true;
        if (lower.contains("500") || lower.contains("502")
                || lower.contains("503") || lower.contains("504")) return true;

        // 인증
        if (lower.contains("unauthorized") || lower.contains("authentication")
                || lower.contains("token expired") || lower.contains("invalid api key")
                || lower.contains("oauth")) return true;

        // Rate Limit
        if (lower.contains("rate limit") || lower.contains("too many requests")
                || lower.contains("quota exceeded") || lower.contains("overloaded")) return true;

        // 서버 일시 장애
        if (lower.contains("internal server error") || lower.contains("bad gateway")
                || lower.contains("service unavailable") || lower.contains("gateway timeout")
                || lower.contains("connection reset") || lower.contains("upstream")) return true;

        return false;
    }

    /** 마크다운 코드펜스(```json ... ```) 제거 */
    private static String stripCodeFence(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).strip();
            }
        }
        return trimmed;
    }

    private String toError(String code, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("error", code);
        error.put("message", message != null ? message : "알 수 없는 오류");
        try {
            return objectMapper.writeValueAsString(error);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"" + code + "\",\"message\":\"직렬화 실패\"}";
        }
    }
}
