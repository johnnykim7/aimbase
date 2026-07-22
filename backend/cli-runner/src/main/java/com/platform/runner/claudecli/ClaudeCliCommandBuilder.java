package com.platform.runner.claudecli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CR-069: Claude CLI 호출 인자 공통 빌더.
 *
 * <p>Worker (LLM 어댑터, stream-json stdio 모드) 와 ClaudeCodeTool (워크플로우 도구, -p 일회성 모드)
 * 양쪽이 같은 정책으로 CLI 를 호출하도록 통일. {@link ToolMode} 단일 스위치로 도구 노출 정책을 결정한다.
 *
 * <p>잠금 정책 (변경 불요) 은 빌더 내부에 하드코딩, 운영 가능 정책은 fluent API 로 호출처가 override.
 *
 * <p>예:
 * <pre>
 * List&lt;String&gt; cmd = ClaudeCliCommandBuilder.builder("claude")
 *     .toolMode(ToolMode.AIMBASE)
 *     .mcpConfigJson(adapterConfig.resolveMcpConfigJson())
 *     .streamJson(true)              // Worker 경로
 *     .model("claude-sonnet-4-6")
 *     .resume(sessionId, false)
 *     .appendSystemPrompt(antiHallucinationPrompt)
 *     .build();
 * </pre>
 */
public final class ClaudeCliCommandBuilder {

    /**
     * Claude CLI 의 도구 노출 모드.
     * <ul>
     *   <li>{@link #AIMBASE}: CLI 네이티브 도구 전면 봉인 + Aimbase MCP 도구만 노출 (default).
     *       정책·감사 완전 경유.</li>
     *   <li>{@link #NATIVE}: CLI 본체 도구만 사용 (MCP 미연결).</li>
     *   <li>{@link #HYBRID}: 양쪽 다 — 실험·디버깅용. 운영에서는 비권장.</li>
     * </ul>
     */
    public enum ToolMode {
        AIMBASE,
        NATIVE,
        HYBRID
    }

    /**
     * CR-126: AIMBASE 모드에서 봉인할 CLI built-in 도구 기본 목록.
     *
     * <p>이전에는 {@code --tools ""} 하나로 봉인했으나, CLI 2.1.x 의 {@code --tools} 는
     * built-in 뿐 아니라 <b>MCP 도구까지 함께</b> 제한한다 (CLI --help: <i>"Use \"\" to
     * disable all tools"</i>). 그 결과 AIMBASE 모드에서 Aimbase MCP 도구가 항상 0개였고,
     * 도구 없이도 요청이 200 으로 끝나 모델이 날조한 응답이 그대로 나갔다.
     *
     * <p>네이티브만 봉인하고 MCP 를 살리려면 {@code --disallowedTools} 로 built-in 을
     * 개별 차단해야 한다. 실측 대조:
     * <pre>
     *   --tools ""                       → MCP 차단 / 네이티브 차단   (의도 불일치)
     *   --allowedTools mcp__server__*    → MCP 허용 / 네이티브 허용   (봉인 실패)
     *   --disallowedTools Bash,Edit,...  → MCP 허용 / 네이티브 차단   (의도 일치)
     * </pre>
     *
     * <p>CLI 버전업으로 built-in 이 늘면 이 상수만으로는 새 도구가 열린 채 남는다.
     * 운영에서는 {@code aimbase.runner.sealed-native-tools} 로 override 할 수 있고,
     * 이 상수는 설정이 비었을 때의 폴백이다.
     */
    public static final List<String> DEFAULT_SEALED_NATIVE_TOOLS = List.of(
            "Task", "Bash", "CronCreate", "CronDelete", "CronList", "DesignSync",
            "Edit", "EnterWorktree", "ExitWorktree", "Monitor", "NotebookEdit",
            "PushNotification", "Read", "RemoteTrigger", "ReportFindings",
            "ScheduleWakeup", "SendMessage", "Skill", "TaskCreate", "TaskGet",
            "TaskList", "TaskOutput", "TaskStop", "TaskUpdate", "ToolSearch",
            "WebFetch", "WebSearch", "Workflow", "Write");

    private final String executable;

    // 모드/정책
    private ToolMode toolMode = ToolMode.AIMBASE;
    private String mcpConfigJson;       // AIMBASE/HYBRID 시 사용. NATIVE 면 무시
    private boolean strictMcpConfig = true;        // 잠금 정책: 글로벌 ~/.claude 격리
    private String permissionMode = "bypassPermissions";  // 잠금: stdio 자동화 환경
    private boolean sealNativeTools = true;        // AIMBASE 시 네이티브 도구 봉인 여부
    // CR-126: 봉인 대상 목록. 비면 DEFAULT_SEALED_NATIVE_TOOLS 폴백
    private List<String> sealedNativeTools = null;

    // 호출 모드 (입출력 형태)
    private boolean streamJson = false;            // Worker 경로
    private String prompt;                          // ClaudeCodeTool 경로 (-p 인자)
    private String outputFormat;                    // stream-json 모드 시 자동, 그 외 명시
    private boolean verbose = false;
    private Integer maxTurns;                       // null 이면 미지정 (모델 자율)

    // 모델/세션
    private String model;
    private String resumeSessionId;
    private boolean forkSession;

    // 시스템 프롬프트
    private String systemPromptOverride;            // --system-prompt (완전교체)
    private String appendSystemPrompt;              // --append-system-prompt (CLI 본체 prompt 끝에 추가)

    // 도구 화이트/블랙리스트 (선택 — AIMBASE 모드에서도 호출처가 override 가능)
    private final Set<String> allowedTools = new LinkedHashSet<>();
    private final Set<String> disallowedTools = new LinkedHashSet<>();
    private String toolsSpec;                       // --tools (도구 셋 자체 제한, 화이트와 다름)
    private boolean toolsSpecExplicit = false;

    private ClaudeCliCommandBuilder(String executable) {
        this.executable = (executable == null || executable.isBlank()) ? "claude" : executable;
    }

    public static ClaudeCliCommandBuilder builder(String executable) {
        return new ClaudeCliCommandBuilder(executable);
    }

    public ClaudeCliCommandBuilder toolMode(ToolMode mode) {
        this.toolMode = mode == null ? ToolMode.AIMBASE : mode;
        return this;
    }

    public ClaudeCliCommandBuilder mcpConfigJson(String json) {
        this.mcpConfigJson = json;
        return this;
    }

    public ClaudeCliCommandBuilder strictMcpConfig(boolean v) {
        this.strictMcpConfig = v;
        return this;
    }

    public ClaudeCliCommandBuilder permissionMode(String mode) {
        // null/blank 면 default(bypassPermissions) 유지. 명시 override 시만 교체.
        if (mode != null && !mode.isBlank()) {
            this.permissionMode = mode;
        }
        return this;
    }

    public ClaudeCliCommandBuilder sealNativeTools(boolean v) {
        this.sealNativeTools = v;
        return this;
    }

    /**
     * CR-126: 봉인할 built-in 도구 목록 override.
     * null/빈 목록이면 {@link #DEFAULT_SEALED_NATIVE_TOOLS} 를 쓴다.
     */
    public ClaudeCliCommandBuilder sealedNativeTools(List<String> tools) {
        this.sealedNativeTools = tools;
        return this;
    }

    public ClaudeCliCommandBuilder streamJson(boolean v) {
        this.streamJson = v;
        return this;
    }

    public ClaudeCliCommandBuilder verbose(boolean v) {
        this.verbose = v;
        return this;
    }

    public ClaudeCliCommandBuilder prompt(String text) {
        this.prompt = text;
        return this;
    }

    public ClaudeCliCommandBuilder outputFormat(String fmt) {
        this.outputFormat = fmt;
        return this;
    }

    public ClaudeCliCommandBuilder maxTurns(Integer n) {
        this.maxTurns = n;
        return this;
    }

    public ClaudeCliCommandBuilder model(String m) {
        this.model = m;
        return this;
    }

    public ClaudeCliCommandBuilder resume(String sessionId, boolean fork) {
        this.resumeSessionId = sessionId;
        this.forkSession = fork;
        return this;
    }

    public ClaudeCliCommandBuilder systemPromptOverride(String text) {
        this.systemPromptOverride = (text == null || text.isBlank()) ? null : text;
        return this;
    }

    public ClaudeCliCommandBuilder appendSystemPrompt(String text) {
        this.appendSystemPrompt = (text == null || text.isBlank()) ? null : text;
        return this;
    }

    public ClaudeCliCommandBuilder allowedTools(List<String> tools) {
        if (tools != null) {
            for (String t : tools) {
                if (t != null && !t.isBlank()) allowedTools.add(t.trim());
            }
        }
        return this;
    }

    public ClaudeCliCommandBuilder disallowedTools(List<String> tools) {
        if (tools != null) {
            for (String t : tools) {
                if (t != null && !t.isBlank()) disallowedTools.add(t.trim());
            }
        }
        return this;
    }

    /**
     * --tools flag (도구 셋 자체 제한). null 이면 미명시.
     *
     * <p><b>주의 (CR-126)</b>: 빈 문자열을 넘기면 CLI 가 <i>모든</i> 도구를 끈다 —
     * built-in 뿐 아니라 <b>MCP 도구까지</b> 사라진다. "네이티브만 봉인" 이 목적이라면
     * 이 메서드가 아니라 {@link #sealNativeTools(boolean)} 를 쓸 것.
     * AIMBASE 모드에서 여기에 ""/"none" 을 넘기면 Aimbase MCP 도구가 0개가 되고,
     * 그 상태로도 요청은 200 으로 끝나 모델이 날조한 응답이 나간다.
     */
    public ClaudeCliCommandBuilder toolsSpec(String spec) {
        this.toolsSpec = spec;
        this.toolsSpecExplicit = true;
        return this;
    }

    public List<String> build() {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);

        // 세션 재개는 prompt 앞에 위치 (CLI 표준)
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            cmd.add("--resume");
            cmd.add(resumeSessionId);
            if (forkSession) cmd.add("--fork-session");
        }

        // -p 모드 (일회성 호출). prompt 가 있으면 인자로, 없으면 stdin 모드.
        if (prompt != null) {
            cmd.add("-p");
            cmd.add(prompt);
        } else {
            cmd.add("-p");
        }

        if (verbose) cmd.add("--verbose");

        if (streamJson) {
            cmd.add("--input-format");
            cmd.add("stream-json");
            cmd.add("--output-format");
            cmd.add("stream-json");
        } else if (outputFormat != null && !outputFormat.isBlank()) {
            cmd.add("--output-format");
            cmd.add(outputFormat);
        }

        if (maxTurns != null && maxTurns > 0) {
            cmd.add("--max-turns");
            cmd.add(String.valueOf(maxTurns));
        }

        // 도구 노출 정책 — toolMode + 명시 override
        applyToolMode(cmd);

        // 권한 모드 (잠금 default = bypassPermissions)
        cmd.add("--permission-mode");
        cmd.add(permissionMode);

        // 시스템 프롬프트
        if (systemPromptOverride != null) {
            cmd.add("--system-prompt");
            cmd.add(systemPromptOverride);
        }
        if (appendSystemPrompt != null) {
            cmd.add("--append-system-prompt");
            cmd.add(appendSystemPrompt);
        }

        // 모델 (마지막 — CLI 가 prompt 후 인자로도 받지만 명시적 위치 안전)
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        return cmd;
    }

    /**
     * 도구 모드별 flag 적용:
     * - AIMBASE: --disallowedTools <built-in...> (네이티브 봉인, CR-126) + --strict-mcp-config + --mcp-config <json>
     * - NATIVE: MCP 미연결 — --strict-mcp-config + --mcp-config {빈} (글로벌 격리만 적용)
     * - HYBRID: 네이티브 + MCP 양쪽 (--tools 봉인 안 함, MCP 는 연결)
     * 호출처가 toolsSpec/allowedTools/disallowedTools 를 명시하면 추가 적용.
     */
    private void applyToolMode(List<String> cmd) {
        // 1. --tools (네이티브 도구 셋 봉인 여부)
        if (toolsSpecExplicit) {
            // 호출처가 명시적으로 결정 (override)
            cmd.add("--tools");
            cmd.add(toolsSpec == null ? "" : toolsSpec);
        } else if (toolMode == ToolMode.AIMBASE && sealNativeTools) {
            // CR-126: --tools "" 는 MCP 도구까지 함께 끄므로 쓰지 않는다.
            // built-in 을 --disallowedTools 로 개별 차단해야 MCP 도구가 살아남는다.
            List<String> seal = (sealedNativeTools == null || sealedNativeTools.isEmpty())
                    ? DEFAULT_SEALED_NATIVE_TOOLS
                    : sealedNativeTools;
            for (String t : seal) {
                if (t != null && !t.isBlank()) {
                    cmd.add("--disallowedTools");
                    cmd.add(t.trim());
                }
            }
        }
        // NATIVE/HYBRID 는 기본적으로 --tools 미명시 → CLI 본체 도구 모두 사용 가능

        // 2. --strict-mcp-config + --mcp-config (글로벌 ~/.claude 격리)
        if (strictMcpConfig) {
            cmd.add("--strict-mcp-config");
        }
        String resolvedMcp = resolveMcpJson();
        cmd.add("--mcp-config");
        cmd.add(resolvedMcp);

        // 3. allowed/disallowed override
        for (String t : allowedTools) {
            cmd.add("--allowedTools");
            cmd.add(t);
        }
        for (String t : disallowedTools) {
            cmd.add("--disallowedTools");
            cmd.add(t);
        }
    }

    private String resolveMcpJson() {
        if (toolMode == ToolMode.NATIVE) {
            return "{\"mcpServers\":{}}";
        }
        // AIMBASE / HYBRID
        if (mcpConfigJson != null && !mcpConfigJson.isBlank()) {
            return mcpConfigJson;
        }
        return "{\"mcpServers\":{}}";
    }

    /** 디버그·테스트용 — 빌더 상태 확인. */
    Set<String> getAllowedToolsForTest() { return Collections.unmodifiableSet(allowedTools); }
    Set<String> getDisallowedToolsForTest() { return Collections.unmodifiableSet(disallowedTools); }
    ToolMode getToolModeForTest() { return toolMode; }
}
