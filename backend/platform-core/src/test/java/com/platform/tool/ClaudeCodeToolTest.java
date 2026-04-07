package com.platform.tool;

import com.platform.tool.builtin.ClaudeCodeCircuitBreaker;
import com.platform.tool.builtin.ClaudeCodeTool;
import com.platform.tool.builtin.ClaudeCodeToolConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClaudeCodeTool 단위 테스트.
 *
 * CLI 실행 자체는 통합 테스트에서 검증하고,
 * 여기서는 입력 검증, 에러 핸들링, 커맨드 빌드 로직을 테스트.
 */
class ClaudeCodeToolTest {

    private ClaudeCodeTool tool;
    private ClaudeCodeToolConfig config;
    private ClaudeCodeCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        config = new ClaudeCodeToolConfig();
        config.setEnabled(true);
        config.setExecutable("echo");  // 실제 claude 대신 echo 사용
        config.setTimeoutSeconds(10);
        config.setMaxTurns(5);
        config.setDefaultAllowedTools("Read,Grep,Glob");
        config.setWorkingDirectory("");

        circuitBreaker = new ClaudeCodeCircuitBreaker();
        tool = new ClaudeCodeTool(config, circuitBreaker, null, null, null);
    }

    @Test
    void getDefinition_shouldReturnValidToolDef() {
        var def = tool.getDefinition();

        assertThat(def.name()).isEqualTo("claude_code");
        assertThat(def.description()).contains("Claude Code CLI");
        assertThat(def.inputSchema()).containsKey("properties");

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) def.inputSchema().get("properties");
        // 기본 파라미터
        assertThat(properties).containsKey("prompt");
        assertThat(properties).containsKey("input_file");
        assertThat(properties).containsKey("output_format");
        assertThat(properties).containsKey("max_turns");
        assertThat(properties).containsKey("allowed_tools");
        assertThat(properties).containsKey("json_schema");
        assertThat(properties).containsKey("working_directory");
        assertThat(properties).containsKey("append_system_prompt");
        assertThat(properties).containsKey("model");
        assertThat(properties).containsKey("effort");
        assertThat(properties).containsKey("permission_mode");
        assertThat(properties).containsKey("cli_options");
        // 새 파라미터 전수 확인
        assertThat(properties).containsKey("disallowed_tools");
        assertThat(properties).containsKey("thinking");
        assertThat(properties).containsKey("max_thinking_tokens");
        assertThat(properties).containsKey("system_prompt");
        assertThat(properties).containsKey("fork_session");
        assertThat(properties).containsKey("tools");
        assertThat(properties).containsKey("mcp_config");
        assertThat(properties).containsKey("max_budget_usd");
        assertThat(properties).containsKey("fallback_model");
        assertThat(properties).containsKey("task_budget");

        @SuppressWarnings("unchecked")
        var required = (List<String>) def.inputSchema().get("required");
        assertThat(required).containsExactly("prompt");
    }

    @Test
    void execute_withMissingPrompt_shouldReturnError() {
        String result = tool.execute(Map.of());

        assertThat(result).contains("INVALID_INPUT");
        assertThat(result).contains("prompt");
    }

    @Test
    void execute_withBlankPrompt_shouldReturnError() {
        String result = tool.execute(Map.of("prompt", "   "));

        assertThat(result).contains("INVALID_INPUT");
    }

    @Test
    void execute_withNonExistentInputFile_shouldReturnFileNotFoundError() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "분석해줘");
        input.put("input_file", "/nonexistent/path/to/file.txt");

        String result = tool.execute(input);

        assertThat(result).contains("FILE_NOT_FOUND");
        assertThat(result).contains("/nonexistent/path/to/file.txt");
    }

    @Test
    void execute_withEchoCommand_shouldSucceed() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "hello world");
        input.put("output_format", "text");

        String result = tool.execute(input);

        assertThat(result).doesNotContain("\"error\"");
        assertThat(result).contains("hello world");
    }

    @Test
    void execute_withCustomMaxTurns_shouldUseProvidedValue() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("max_turns", 20);

        String result = tool.execute(input);

        assertThat(result).contains("--max-turns");
        assertThat(result).contains("20");
    }

    @Test
    void execute_withCustomAllowedTools_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("allowed_tools", List.of("Read", "Write", "Bash"));

        String result = tool.execute(input);

        assertThat(result).contains("--allowedTools");
        assertThat(result).contains("Read");
        assertThat(result).contains("Write");
        assertThat(result).contains("Bash");
    }

    @Test
    void execute_withJsonSchema_shouldPassToCommand() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "summary", Map.of("type", "string")
                )
        );

        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("json_schema", schema);

        String result = tool.execute(input);

        assertThat(result).contains("--json-schema");
    }

    @Test
    void execute_withAppendSystemPrompt_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("append_system_prompt", "항상 한국어로 응답하라");

        String result = tool.execute(input);

        assertThat(result).contains("--append-system-prompt");
        assertThat(result).contains("항상 한국어로 응답하라");
    }

    @Test
    void execute_withTimeout_shouldReturnTimeoutError() {
        config.setExecutable("bash");
        config.setTimeoutSeconds(1);
        var timeoutTool = new ClaudeCodeTool(config, new ClaudeCodeCircuitBreaker(), null, null, null);

        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "sleep 60");
        input.put("output_format", "text");

        String result = timeoutTool.execute(input);

        assertThat(result).containsAnyOf("TIMEOUT", "EXIT_");
    }

    @Test
    void execute_defaultsToTextOutputFormat() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");

        String result = tool.execute(input);

        assertThat(result).isNotNull();
    }

    @Test
    void execute_usesDefaultAllowedToolsFromConfig() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");

        String result = tool.execute(input);

        assertThat(result).contains("Read");
        assertThat(result).contains("Grep");
        assertThat(result).contains("Glob");
    }

    // ── PRD-116: cli_options 테스트 ──

    @Test
    void execute_withCliOptions_shouldAppendToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("cli_options", Map.of("--verbose", "", "--color", "always"));

        String result = tool.execute(input);

        assertThat(result).contains("--verbose");
        assertThat(result).contains("--color");
        assertThat(result).contains("always");
    }

    @Test
    void execute_cliOptionsDuplicateWithNamedParam_shouldBeIgnored() {
        // --max-turns는 named param으로 이미 처리되므로 cli_options에서 무시
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("max_turns", 10);
        input.put("cli_options", Map.of("--max-turns", "99"));

        String result = tool.execute(input);

        // named param 값 10이 사용되어야 함
        assertThat(result).contains("10");
    }

    @Test
    void execute_withBlockedOption_shouldReturnError() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("cli_options", Map.of("--dangerously-skip-permissions", ""));

        String result = tool.execute(input);

        assertThat(result).contains("BLOCKED_OPTION");
        assertThat(result).contains("dangerously-skip-permissions");
    }

    // ── PRD-121: model/effort/permission_mode 테스트 ──

    @Test
    void execute_withModel_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("model", "claude-sonnet-4-20250514");

        String result = tool.execute(input);

        assertThat(result).contains("--model");
        assertThat(result).contains("claude-sonnet-4-20250514");
    }

    @Test
    void execute_withEffort_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("effort", "high");

        String result = tool.execute(input);

        assertThat(result).contains("--reasoning-effort");
        assertThat(result).contains("high");
    }

    @Test
    void execute_withPermissionMode_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("permission_mode", "plan");

        String result = tool.execute(input);

        assertThat(result).contains("--permission-mode");
        assertThat(result).contains("plan");
    }

    @Test
    void getDefinition_modelShouldHaveEnumValues() {
        var def = tool.getDefinition();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) def.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var modelProp = (Map<String, Object>) properties.get("model");

        assertThat(modelProp).containsKey("enum");
        @SuppressWarnings("unchecked")
        var enumValues = (List<String>) modelProp.get("enum");
        assertThat(enumValues).contains("claude-sonnet-4-20250514", "claude-opus-4-20250514");
    }

    @Test
    void getDefinition_effortShouldHaveEnumValues() {
        var def = tool.getDefinition();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) def.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var effortProp = (Map<String, Object>) properties.get("effort");

        assertThat(effortProp).containsKey("enum");
        @SuppressWarnings("unchecked")
        var enumValues = (List<String>) effortProp.get("enum");
        assertThat(enumValues).containsExactly("low", "medium", "high", "max");
    }

    @Test
    void getDefinition_outputFormatShouldIncludeStreamJson() {
        var def = tool.getDefinition();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) def.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var outputFormatProp = (Map<String, Object>) properties.get("output_format");
        @SuppressWarnings("unchecked")
        var enumValues = (List<String>) outputFormatProp.get("enum");

        assertThat(enumValues).containsExactly("text", "json", "stream-json");
    }

    @Test
    void getDefinition_thinkingShouldHaveEnumValues() {
        var def = tool.getDefinition();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) def.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var thinkingProp = (Map<String, Object>) properties.get("thinking");
        @SuppressWarnings("unchecked")
        var enumValues = (List<String>) thinkingProp.get("enum");

        assertThat(enumValues).containsExactly("adaptive", "enabled", "disabled");
    }

    // ── 새 파라미터 CLI 전달 테스트 ──

    @Test
    void execute_withDisallowedTools_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("disallowed_tools", List.of("Bash", "FileWrite"));

        String result = tool.execute(input);

        assertThat(result).contains("--disallowedTools");
        assertThat(result).contains("Bash");
        assertThat(result).contains("FileWrite");
    }

    @Test
    void execute_withThinking_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("thinking", "adaptive");
        input.put("max_thinking_tokens", 8000);

        String result = tool.execute(input);

        assertThat(result).contains("--thinking");
        assertThat(result).contains("adaptive");
        assertThat(result).contains("--max-thinking-tokens");
        assertThat(result).contains("8000");
    }

    @Test
    void execute_withSystemPrompt_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("system_prompt", "You are a code reviewer.");

        String result = tool.execute(input);

        assertThat(result).contains("--system-prompt");
        assertThat(result).contains("You are a code reviewer.");
    }

    @Test
    void execute_withForkSession_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("continue_mode", "continue");
        input.put("fork_session", true);

        String result = tool.execute(input);

        assertThat(result).contains("--fork-session");
    }

    @Test
    void execute_withToolsSpec_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("tools", "Bash,Read,Edit");

        String result = tool.execute(input);

        assertThat(result).contains("--tools");
        assertThat(result).contains("Bash,Read,Edit");
    }

    @Test
    void execute_withMcpConfig_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("mcp_config", List.of("/etc/mcp/flowguard.json"));

        String result = tool.execute(input);

        assertThat(result).contains("--mcp-config");
        assertThat(result).contains("/etc/mcp/flowguard.json");
    }

    @Test
    void execute_withMaxBudgetUsd_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("max_budget_usd", 0.5);

        String result = tool.execute(input);

        assertThat(result).contains("--max-budget-usd");
        assertThat(result).contains("0.5");
    }

    @Test
    void execute_withFallbackModel_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("fallback_model", "claude-haiku-4-20250414");

        String result = tool.execute(input);

        assertThat(result).contains("--fallback-model");
        assertThat(result).contains("claude-haiku-4-20250414");
    }

    @Test
    void execute_withTaskBudget_shouldPassToCommand() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("task_budget", 10000);

        String result = tool.execute(input);

        assertThat(result).contains("--task-budget");
        assertThat(result).contains("10000");
    }

    // ── PRD-118: 서킷 브레이커 테스트 ──

    @Test
    void execute_whenCircuitOpen_shouldReturnError() {
        // 3회 연속 실패로 서킷 OPEN 유도
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.getState())
                .isEqualTo(ClaudeCodeCircuitBreaker.State.OPEN);

        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");

        String result = tool.execute(input);

        assertThat(result).contains("CIRCUIT_OPEN");
    }

    @Test
    void circuitBreaker_successResetsToClose() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.getState())
                .isEqualTo(ClaudeCodeCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getConsecutiveFailures()).isZero();
    }

    @Test
    void circuitBreaker_manualReset() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.getState())
                .isEqualTo(ClaudeCodeCircuitBreaker.State.OPEN);

        circuitBreaker.reset();

        assertThat(circuitBreaker.getState())
                .isEqualTo(ClaudeCodeCircuitBreaker.State.CLOSED);
    }

    // ── 세션 관리 테스트 ──

    @Test
    void execute_defaultSessionPersistence_shouldNotAddNoSessionFlag() {
        // Config 기본값 true → --no-session-persistence 없어야 함
        config.setDefaultSessionPersistence(true);
        var sessionTool = new ClaudeCodeTool(config, new ClaudeCodeCircuitBreaker(), null, null, null);

        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");

        String result = sessionTool.execute(input);

        assertThat(result).doesNotContain("--no-session-persistence");
    }

    @Test
    void execute_sessionPersistenceDisabled_shouldAddNoSessionFlag() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "test");
        input.put("output_format", "text");
        input.put("session_persistence", false);

        String result = tool.execute(input);

        assertThat(result).contains("--no-session-persistence");
    }

    @Test
    void execute_continueMode_shouldAddContinueFlag() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "이전 작업 이어서 진행");
        input.put("output_format", "text");
        input.put("continue_mode", "continue");

        String result = tool.execute(input);

        assertThat(result).contains("--continue");
    }

    @Test
    void execute_resumeMode_shouldAddResumeWithSessionId() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "세션 재개");
        input.put("output_format", "text");
        input.put("continue_mode", "resume");
        input.put("session_id", "sess_abc123");

        String result = tool.execute(input);

        assertThat(result).contains("--resume");
        assertThat(result).contains("sess_abc123");
    }

    @Test
    void execute_resumeModeWithoutSessionId_shouldReturnError() {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "세션 재개");
        input.put("continue_mode", "resume");

        String result = tool.execute(input);

        assertThat(result).contains("INVALID_INPUT");
        assertThat(result).contains("session_id");
    }

    @Test
    void getDefinition_shouldContainContinueModeAndSessionId() {
        var def = tool.getDefinition();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) def.inputSchema().get("properties");
        assertThat(properties).containsKey("continue_mode");
        assertThat(properties).containsKey("session_id");

        @SuppressWarnings("unchecked")
        var continueProp = (Map<String, Object>) properties.get("continue_mode");
        @SuppressWarnings("unchecked")
        var enumValues = (List<String>) continueProp.get("enum");
        assertThat(enumValues).containsExactly("new", "continue", "resume");
    }

    @Test
    void getDefinition_cliOptionsShouldBeObjectType() {
        var def = tool.getDefinition();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) def.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var cliOptionsProp = (Map<String, Object>) properties.get("cli_options");

        assertThat(cliOptionsProp.get("type")).isEqualTo("object");
        assertThat(cliOptionsProp).containsKey("additionalProperties");
    }
}
