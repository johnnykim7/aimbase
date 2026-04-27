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

    private final String executable;

    // 모드/정책
    private ToolMode toolMode = ToolMode.AIMBASE;
    private String mcpConfigJson;       // AIMBASE/HYBRID 시 사용. NATIVE 면 무시
    private boolean strictMcpConfig = true;        // 잠금 정책: 글로벌 ~/.claude 격리
    private String permissionMode = "bypassPermissions";  // 잠금: stdio 자동화 환경
    private boolean sealNativeTools = true;        // AIMBASE 시 --tools "" 적용 여부

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
     * 빈 문자열은 "네이티브 도구 전면 봉인" 의미로 해석 — sealNativeTools 와 동일 효과지만
     * 호출처가 명시적으로 컨트롤하고 싶을 때 사용.
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
     * - AIMBASE: --tools "" (네이티브 봉인) + --strict-mcp-config + --mcp-config <json>
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
            cmd.add("--tools");
            cmd.add("");
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
