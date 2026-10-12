package com.platform.tool.builtin;

import com.platform.config.WorkspaceProperties;
import com.platform.skill.*;
import com.platform.tool.*;
import com.platform.tool.model.UnifiedToolDef;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CR-147: 스킬 묶음 스크립트나 모델이 작업장에 쓴 스크립트를 실행 컨테이너에서 돌린다(T3-17 3.4, BIZ-122).
 * name 이 있으면 묶음을 작업장 .skills/ 에 풀고 묶음 기준 경로로, 없으면 작업장 기준 경로로 찾는다.
 */
@Component
public class SkillRunTool implements EnhancedToolExecutor {

    public static final String NAME = "skill_run";

    private final SkillCatalog catalog;
    private final SkillWorkspaceInstaller installer;
    private final SkillSandboxClient sandbox;
    private final SkillBundleSettings settings;
    private final WorkspaceProperties workspaceProperties;

    public SkillRunTool(SkillCatalog catalog, SkillWorkspaceInstaller installer, SkillSandboxClient sandbox,
                        SkillBundleSettings settings, WorkspaceProperties workspaceProperties) {
        this.catalog = catalog;
        this.installer = installer;
        this.sandbox = sandbox;
        this.settings = settings;
        this.workspaceProperties = workspaceProperties;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                NAME,
                "Runs a script in an isolated container with no internet. "
                        + "With name, script is a path inside that skill bundle (e.g. scripts/check.py). "
                        + "Without name, script is a path in the workspace, such as a file you wrote. "
                        + "Runs in the workspace directory. Supported: .py, .js, .mjs, .sh. "
                        + "Returns exit code, stdout, stderr and the workspace files the script created or changed.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string",
                                        "description", "Skill name, when running a script from a skill bundle"),
                                "script", Map.of("type", "string", "description", "Script path"),
                                "args", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Command line arguments"),
                                "timeout_seconds", Map.of("type", "integer", "description", "Time limit in seconds")
                        ),
                        "required", List.of("script")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                NAME, "1.0",
                ToolScope.NATIVE, PermissionLevel.RESTRICTED_WRITE,
                false, false, false, false,
                RetryPolicy.NONE,
                List.of("skill", "script"),
                List.of("execute", "write")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        if (ctx == null || ctx.workspacePath() == null || ctx.workspacePath().isBlank()) {
            return ToolResult.error("BIZ-122: 작업장이 없는 호출에서는 스크립트를 실행할 수 없습니다");
        }
        Path workspace = Path.of(ctx.workspacePath()).toAbsolutePath().normalize();
        if (!workspaceProperties.isInsideWhitelist(workspace)) {
            return ToolResult.error("BIZ-122: 작업장이 허용된 경로 밖입니다: " + workspace);
        }
        if (!(input.get("script") instanceof String script) || script.isBlank()) {
            return ToolResult.error("INVALID_INPUT: script is required");
        }
        List<String> args;
        Integer requestedTimeout;
        try {
            args = toArgs(input.get("args"));
            requestedTimeout = toInteger(input.get("timeout_seconds"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("INVALID_INPUT: " + e.getMessage());
        }

        Path scriptPath;
        try {
            if (input.get("name") instanceof String name && !name.isBlank()) {
                ResolvedSkill skill = catalog.resolveForCall(name.trim(), ctx.skillSet());
                Path bundleDir = installer.install(skill, workspace);
                scriptPath = bundleDir.resolve(script.trim()).normalize();
                if (!scriptPath.startsWith(bundleDir)) {
                    return ToolResult.error("BIZ-122: 스크립트 경로가 스킬 묶음 밖입니다: " + script);
                }
            } else {
                scriptPath = workspace.resolve(script.trim()).normalize();
                if (!scriptPath.startsWith(workspace)) {
                    return ToolResult.error("BIZ-122: 스크립트 경로가 작업장 밖입니다: " + script);
                }
            }
        } catch (SkillNotFoundException | UncheckedIOException | IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }

        int max = settings.scriptTimeoutMaxSeconds();
        int timeout = requestedTimeout == null ? settings.scriptTimeoutSeconds() : requestedTimeout;
        timeout = Math.max(1, Math.min(timeout, max));

        SkillSandboxClient.RunResult result;
        try {
            result = sandbox.run(new SkillSandboxClient.RunRequest(workspace.toString(), scriptPath.toString(),
                    args, timeout, settings.scriptOutputMaxBytes()));
        } catch (SkillSandboxException e) {
            return ToolResult.error(e.getMessage());
        }
        return ToolResult.ok(format(result, settings.scriptOutputMaxBytes()),
                "exit " + result.exitCode() + (result.timedOut() ? " (timed out after " + timeout + "s)" : ""));
    }

    static String format(SkillSandboxClient.RunResult r, int maxOutputBytes) {
        StringBuilder out = new StringBuilder();
        out.append("exit_code: ").append(r.exitCode()).append('\n')
                .append("timed_out: ").append(r.timedOut()).append('\n')
                .append("duration_ms: ").append(r.durationMs()).append('\n')
                .append("changed_files (").append(r.changedFilesTotal()).append("):\n");
        for (String f : r.changedFiles()) {
            out.append("- ").append(f).append('\n');
        }
        if (r.changedFilesTotal() > r.changedFiles().size()) {
            out.append("- ... ").append(r.changedFilesTotal() - r.changedFiles().size()).append(" more\n");
        }
        appendStream(out, "stdout", r.stdout(), r.stdoutTruncated(), maxOutputBytes);
        appendStream(out, "stderr", r.stderr(), r.stderrTruncated(), maxOutputBytes);
        return out.toString();
    }

    private static void appendStream(StringBuilder out, String label, String text, boolean truncated, int max) {
        out.append(label).append(":\n").append(text == null ? "" : text);
        if (text != null && !text.isEmpty() && !text.endsWith("\n")) {
            out.append('\n');
        }
        if (truncated) {
            out.append("[").append(label).append(" truncated at ").append(max).append(" bytes]\n");
        }
    }

    private static List<String> toArgs(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("args must be an array of strings");
        }
        List<String> args = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o == null) throw new IllegalArgumentException("args must not contain null");
            args.add(o.toString());
        }
        return args;
    }

    /** CLI 통로는 정수를 문자열로 보내기도 한다. */
    private static Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number num) return num.intValue();
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timeout_seconds must be an integer");
        }
    }
}
