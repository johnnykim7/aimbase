package com.platform.runner.claudecli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-069: ClaudeCliCommandBuilder 단위 테스트.
 *
 * <p>잠금 정책 (--strict-mcp-config / --permission-mode bypassPermissions / --tools "" sealing) 이
 * Worker / ClaudeCodeTool 양쪽 호출에서 자동 적용되는지 + tool_mode 별 분기 + 호출처 override 동작 검증.
 */
class ClaudeCliCommandBuilderTest {

    private static final String AIMBASE_MCP =
            "{\"mcpServers\":{\"aimbase\":{\"command\":\"java\",\"args\":[\"-jar\",\"agent.jar\",\"--mcp-stdio\"]}}}";

    @Test
    @DisplayName("default: AIMBASE 모드 + 잠금 정책(strict-mcp-config + bypassPermissions + --tools '' sealing)")
    void default_aimbase_mode_applies_lockdown_policy() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .mcpConfigJson(AIMBASE_MCP)
                .build();

        assertThat(cmd).containsSequence("--tools", "");                                  // 네이티브 봉인
        assertThat(cmd).containsSequence("--strict-mcp-config");                          // 글로벌 격리
        assertThat(cmd).containsSequence("--mcp-config", AIMBASE_MCP);                    // Aimbase MCP 연결
        assertThat(cmd).containsSequence("--permission-mode", "bypassPermissions");       // stdio 자동화
    }

    @Test
    @DisplayName("CR-104: allowedTools 가 --allowedTools 플래그로 도구별 추가된다 (네이티브 봉인과 공존)")
    void cr104_allowedTools_emits_per_tool_flag() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .mcpConfigJson(AIMBASE_MCP)
                .allowedTools(List.of("mcp__aimbase-server__file_write",
                        "mcp__aimbase-server__builtin_grep"))
                .build();

        // --tools "" (네이티브 봉인) 는 그대로 — allowedTools 는 MCP 도구 화이트리스트로 별개 적용
        assertThat(cmd).containsSequence("--tools", "");
        assertThat(cmd).containsSequence("--allowedTools", "mcp__aimbase-server__file_write");
        assertThat(cmd).containsSequence("--allowedTools", "mcp__aimbase-server__builtin_grep");
    }

    @Test
    @DisplayName("CR-104: allowedTools 미지정(null) 이면 --allowedTools 플래그 미출력 (endpoint 전체 사용)")
    void cr104_no_allowedTools_means_no_flag() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .mcpConfigJson(AIMBASE_MCP)
                .allowedTools(null)
                .build();

        assertThat(cmd).doesNotContain("--allowedTools");
    }

    @Test
    @DisplayName("AIMBASE 모드: Worker 시나리오 (stream-json + model + resume)")
    void aimbase_worker_scenario() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .toolMode(ClaudeCliCommandBuilder.ToolMode.AIMBASE)
                .mcpConfigJson(AIMBASE_MCP)
                .streamJson(true)
                .verbose(true)
                .model("claude-sonnet-4-6")
                .resume("session-A", false)
                .appendSystemPrompt("Aimbase rules")
                .build();

        assertThat(cmd).containsSequence("claude", "--resume", "session-A", "-p");
        assertThat(cmd).containsSequence("--input-format", "stream-json", "--output-format", "stream-json");
        assertThat(cmd).contains("--verbose");
        assertThat(cmd).containsSequence("--tools", "");
        assertThat(cmd).containsSequence("--strict-mcp-config");
        assertThat(cmd).containsSequence("--permission-mode", "bypassPermissions");
        assertThat(cmd).containsSequence("--append-system-prompt", "Aimbase rules");
        assertThat(cmd).containsSequence("--model", "claude-sonnet-4-6");
    }

    @Test
    @DisplayName("AIMBASE 모드: ClaudeCodeTool 시나리오 (-p prompt + max-turns + json output)")
    void aimbase_codetool_scenario() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("/usr/local/bin/claude")
                .toolMode(ClaudeCliCommandBuilder.ToolMode.AIMBASE)
                .mcpConfigJson(AIMBASE_MCP)
                .prompt("List files in workspace")
                .outputFormat("json")
                .maxTurns(5)
                .model("claude-sonnet-4-6")
                .build();

        assertThat(cmd).startsWith("/usr/local/bin/claude", "-p", "List files in workspace");
        assertThat(cmd).containsSequence("--output-format", "json");
        assertThat(cmd).containsSequence("--max-turns", "5");
        assertThat(cmd).containsSequence("--tools", "");                                  // AIMBASE 잠금
        assertThat(cmd).containsSequence("--mcp-config", AIMBASE_MCP);
        assertThat(cmd).containsSequence("--permission-mode", "bypassPermissions");
    }

    @Test
    @DisplayName("NATIVE 모드: CLI 본체 도구만 — --tools 미명시 + MCP 빈 설정")
    void native_mode_uses_cli_native_tools_only() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .toolMode(ClaudeCliCommandBuilder.ToolMode.NATIVE)
                .mcpConfigJson(AIMBASE_MCP)  // 명시해도 NATIVE 면 무시됨
                .build();

        assertThat(cmd).doesNotContain("");                                                // --tools "" 없음
        assertThat(cmd).containsSequence("--strict-mcp-config");                          // 글로벌 격리는 유지
        assertThat(cmd).containsSequence("--mcp-config", "{\"mcpServers\":{}}");          // MCP 빈 설정으로 강제
    }

    @Test
    @DisplayName("HYBRID 모드: 네이티브 + MCP 둘 다 — --tools 미명시 + Aimbase MCP 연결")
    void hybrid_mode_allows_both() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .toolMode(ClaudeCliCommandBuilder.ToolMode.HYBRID)
                .mcpConfigJson(AIMBASE_MCP)
                .build();

        assertThat(cmd).doesNotContain("");                                                // 네이티브 안 봉인
        assertThat(cmd).containsSequence("--strict-mcp-config");
        assertThat(cmd).containsSequence("--mcp-config", AIMBASE_MCP);
    }

    @Test
    @DisplayName("호출처가 toolsSpec/allowedTools/disallowedTools 명시 시 추가 적용")
    void explicit_tool_lists_are_appended() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .toolMode(ClaudeCliCommandBuilder.ToolMode.HYBRID)
                .mcpConfigJson(AIMBASE_MCP)
                .toolsSpec("Bash,Read,Edit")
                .allowedTools(List.of("Glob", "Grep"))
                .disallowedTools(List.of("WebFetch", "WebSearch"))
                .build();

        assertThat(cmd).containsSequence("--tools", "Bash,Read,Edit");
        assertThat(cmd).containsSequence("--allowedTools", "Glob");
        assertThat(cmd).containsSequence("--allowedTools", "Grep");
        assertThat(cmd).containsSequence("--disallowedTools", "WebFetch");
        assertThat(cmd).containsSequence("--disallowedTools", "WebSearch");
    }

    @Test
    @DisplayName("permissionMode override: 명시 시 default(bypassPermissions) 교체")
    void permission_mode_override() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .permissionMode("acceptEdits")
                .build();

        assertThat(cmd).containsSequence("--permission-mode", "acceptEdits");
        assertThat(cmd).doesNotContain("bypassPermissions");
    }

    @Test
    @DisplayName("permissionMode null/blank → default(bypassPermissions) 유지 (잠금 정책)")
    void permission_mode_null_keeps_default() {
        List<String> cmd1 = ClaudeCliCommandBuilder.builder("claude").permissionMode(null).build();
        List<String> cmd2 = ClaudeCliCommandBuilder.builder("claude").permissionMode("").build();
        List<String> cmd3 = ClaudeCliCommandBuilder.builder("claude").permissionMode("   ").build();

        assertThat(cmd1).containsSequence("--permission-mode", "bypassPermissions");
        assertThat(cmd2).containsSequence("--permission-mode", "bypassPermissions");
        assertThat(cmd3).containsSequence("--permission-mode", "bypassPermissions");
    }

    @Test
    @DisplayName("strictMcpConfig false override: --strict-mcp-config 생략 (실험용)")
    void strict_mcp_config_can_be_disabled() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .strictMcpConfig(false)
                .build();

        assertThat(cmd).doesNotContain("--strict-mcp-config");
    }

    @Test
    @DisplayName("systemPromptOverride + appendSystemPrompt 동시 명시 가능")
    void system_prompt_override_and_append_coexist() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .systemPromptOverride("Replace whole prompt")
                .appendSystemPrompt("Add anti-hallucination rules")
                .build();

        assertThat(cmd).containsSequence("--system-prompt", "Replace whole prompt");
        assertThat(cmd).containsSequence("--append-system-prompt", "Add anti-hallucination rules");
    }

    @Test
    @DisplayName("resume + fork-session: --resume <id> --fork-session 순서로 prompt 앞에")
    void resume_with_fork_session() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .resume("sess-X", true)
                .build();

        int idxResume = cmd.indexOf("--resume");
        int idxFork = cmd.indexOf("--fork-session");
        int idxPrompt = cmd.indexOf("-p");
        assertThat(idxResume).isLessThan(idxFork).isLessThan(idxPrompt);
    }

    @Test
    @DisplayName("AIMBASE + sealNativeTools=false: 네이티브 안 봉인하지만 MCP 는 연결")
    void aimbase_without_sealing_keeps_native_but_adds_mcp() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .toolMode(ClaudeCliCommandBuilder.ToolMode.AIMBASE)
                .sealNativeTools(false)
                .mcpConfigJson(AIMBASE_MCP)
                .build();

        assertThat(cmd).doesNotContain("");  // --tools "" 없음
        assertThat(cmd).containsSequence("--mcp-config", AIMBASE_MCP);
    }

    @Test
    @DisplayName("toolsSpec 호출처 명시(빈 문자열): 명시 우선 — sealing 미적용 모드에서도 봉인")
    void tools_spec_explicit_empty_seals_natives_in_native_mode() {
        List<String> cmd = ClaudeCliCommandBuilder.builder("claude")
                .toolMode(ClaudeCliCommandBuilder.ToolMode.NATIVE)
                .toolsSpec("")  // 호출처가 명시적으로 봉인 요청
                .build();

        assertThat(cmd).containsSequence("--tools", "");
    }
}
