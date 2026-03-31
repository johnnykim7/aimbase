package com.platform.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.UnifiedToolDef;
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
            "--dangerously-skip-permissions=true"
    );

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
                                    "enum", List.of("text", "json"),
                                    "description", "출력 포맷. 후속 Step 연결 시 json 권장 (기본: text)"
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
                            Map.entry("json_schema", Map.of(
                                    "type", "object",
                                    "description", "출력 JSON 스키마 강제 (선택, 구조화 결과 필요 시)"
                            )),
                            Map.entry("working_directory", Map.of(
                                    "type", "string",
                                    "description", "작업 디렉토리 (기본: 시스템 설정값)"
                            )),
                            Map.entry("append_system_prompt", Map.of(
                                    "type", "string",
                                    "description", "시스템 프롬프트에 추가할 지시 (선택)"
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
                                    "enum", List.of("low", "medium", "high"),
                                    "description", "추론 노력 수준 (기본: medium)"
                            )),
                            Map.entry("permission_mode", Map.of(
                                    "type", "string",
                                    "enum", List.of("default", "plan", "bypassPermissions"),
                                    "description", "권한 모드. bypassPermissions는 워크플로우 명시 설정 시만 허용"
                            )),
                            Map.entry("session_persistence", Map.of(
                                    "type", "boolean",
                                    "description", "세션 영속 활성화 (기본: false). true면 --continue/--resume으로 이전 작업 재개 가능"
                            )),
                            Map.entry("cli_options", Map.of(
                                    "type", "object",
                                    "additionalProperties", Map.of("type", "string"),
                                    "description", "추가 CLI 옵션 맵 (예: {\"--verbose\": \"\", \"--continue\": \"session_id\"}). "
                                            + "키는 옵션명(--포함), 값은 옵션 인수(플래그면 빈 문자열)"
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
        String appendSystemPrompt = (String) input.getOrDefault("append_system_prompt",
                config.getDefaultSystemPrompt());
        String model = (String) input.getOrDefault("model", null);
        String effort = (String) input.getOrDefault("effort", null);
        String permissionMode = (String) input.getOrDefault("permission_mode", null);
        boolean sessionPersistence = input.containsKey("session_persistence")
                ? Boolean.TRUE.equals(input.get("session_persistence"))
                : false;
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
                    allowedTools, jsonSchema, appendSystemPrompt, model,
                    effort, permissionMode, sessionPersistence, cliOptions);

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

            // API 키가 설정되어 있으면 프로세스 환경변수로 전달 (서버/AWS 배포용)
            String apiKey = config.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                pb.environment().put("ANTHROPIC_API_KEY", apiKey);
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

            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream())
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

            // json 포맷이면 결과에서 실제 응답 텍스트 추출
            if ("json".equals(outputFormat)) {
                return extractResultFromJson(stdout);
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
    private List<String> buildCommand(String prompt, String outputFormat, int maxTurns,
                                       List<String> allowedTools, Map<String, Object> jsonSchema,
                                       String appendSystemPrompt, String model,
                                       String effort, String permissionMode,
                                       boolean sessionPersistence,
                                       Map<String, String> cliOptions) {
        // named params가 이미 처리하는 옵션 — cli_options에서 중복 방지
        Set<String> handledOptions = Set.of(
                "-p", "--output-format", "--max-turns", "--allowedTools",
                "--json-schema", "--append-system-prompt", "--model",
                "--add-dir", "--no-session-persistence", "--permission-mode",
                "--reasoning-effort"
        );

        List<String> cmd = new ArrayList<>();
        cmd.add(config.getExecutable());
        cmd.add("-p");
        cmd.add(prompt);
        cmd.add("--output-format");
        cmd.add(outputFormat);
        cmd.add("--max-turns");
        cmd.add(String.valueOf(maxTurns));

        // 세션 영속 비활성이 기본, 워크플로우에서 명시적으로 활성화 가능 (PRD-120)
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

        if (jsonSchema != null && !jsonSchema.isEmpty()) {
            try {
                cmd.add("--json-schema");
                cmd.add(objectMapper.writeValueAsString(jsonSchema));
            } catch (JsonProcessingException e) {
                log.warn("JSON 스키마 직렬화 실패, 스키마 무시: {}", e.getMessage());
            }
        }

        if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(appendSystemPrompt);
        }

        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        if (effort != null && !effort.isBlank()) {
            cmd.add("--reasoning-effort");
            cmd.add(effort);
        }

        if (permissionMode != null && !permissionMode.isBlank()) {
            cmd.add("--permission-mode");
            cmd.add(permissionMode);
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
                if (result instanceof String) {
                    return (String) result;
                }
                return objectMapper.writeValueAsString(result);
            }
            return jsonOutput;
        } catch (Exception e) {
            log.debug("JSON 결과 추출 실패, 원본 반환: {}", e.getMessage());
            return jsonOutput;
        }
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
     * 풀 매니저를 통한 사이드카 실행 시도.
     * 가용 계정이 있으면 실행 결과 반환, 없으면 null (로컬 폴백).
     */
    private String tryExecuteViaPool(Map<String, Object> input, List<String> command,
                                      String workDir, int timeoutSeconds,
                                      String inputFile, String outputFormat) {
        String explicitAccountId = (String) input.get("_agent_account_id");
        String tenantId = TenantContext.hasTenant() ? TenantContext.getTenantId() : null;

        AgentAccountEntity account;
        if (explicitAccountId != null) {
            account = poolManager.getAccount(explicitAccountId);
            if (account == null) {
                log.warn("명시된 계정 없음: {}, 로컬 폴백", explicitAccountId);
                return null;
            }
        } else {
            account = poolManager.resolveAccount("claude_code", tenantId, null);
            if (account == null) {
                return null; // 로컬 폴백
            }
        }

        // 계정별 서킷 브레이커 체크
        if (!poolManager.getCircuitBreaker(account.getId()).allowRequest()) {
            log.warn("[{}] 서킷 OPEN, 다른 계정 시도 또는 로컬 폴백", account.getId());
            return null;
        }

        var result = poolManager.executeViaHttp(account, command, workDir, timeoutSeconds, inputFile);

        if (result.exitCode() != 0) {
            log.error("[{}] 사이드카 실패: exit={}, stderr={}", account.getId(), result.exitCode(), result.stderr());
            poolManager.recordFailure(account.getId());
            handleFailure("EXIT_" + result.exitCode(), result.stderr().isBlank() ? result.stdout() : result.stderr());
            return toError("EXIT_" + result.exitCode(), result.stderr().isBlank() ? result.stdout() : result.stderr());
        }

        poolManager.recordSuccess(account.getId());

        log.info("[{}] 사이드카 완료: output_length={}", account.getId(), result.stdout().length());

        if ("json".equals(outputFormat)) {
            return extractResultFromJson(result.stdout());
        }
        return result.stdout();
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
