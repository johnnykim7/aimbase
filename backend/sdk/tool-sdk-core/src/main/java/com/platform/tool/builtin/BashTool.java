package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * CR-037 PRD-241: 워크스페이스 내 셸 명령 직접 실행.
 * ClaudeCodeTool 경유 없이 ProcessBuilder로 직접 실행.
 * 위험 명령 차단 + 타임아웃 + stdout/stderr 분리 캡처.
 */
public class BashTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final long DEFAULT_TIMEOUT_MS = 120_000;
    private static final long MAX_TIMEOUT_MS = 600_000;
    private static final int MAX_OUTPUT_BYTES = 1_048_576; // 1MB

    /** 절대 실행 금지 명령 패턴 */
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "rm -rf /", "rm -rf /*", "mkfs", "shutdown", "reboot", "halt",
            "dd if=", "format", "fdisk", "parted", "wipefs",
            ":(){", "fork bomb", ">(){ :|:& };:",
            "chmod -R 777 /", "chown -R", "passwd",
            "curl|sh", "curl|bash", "wget|sh", "wget|bash"
    );

    /** 위험 명령 프리픽스 (추가 경고) */
    private static final Set<String> DANGEROUS_PREFIXES = Set.of(
            "sudo ", "su ", "kill -9", "killall", "pkill",
            "docker rm", "docker rmi", "docker system prune",
            "git push --force", "git reset --hard"
    );

    private final WorkspaceResolver workspaceResolver;

    public BashTool(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "bash",
                "Execute a shell command in the workspace directory. " +
                        "Returns stdout, stderr, and exit code. " +
                        "Dangerous commands (rm -rf /, mkfs, shutdown, etc.) are blocked. " +
                        "Default timeout: 120 seconds, max: 600 seconds.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string",
                                        "description", "Shell command to execute"),
                                "timeout_ms", Map.of("type", "integer",
                                        "description", "Timeout in milliseconds (default: 120000, max: 600000)"),
                                "working_directory", Map.of("type", "string",
                                        "description", "Working directory override (default: workspace root)")
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "bash", "1.0", ToolScope.BUILTIN,
                PermissionLevel.FULL,
                false, false, true, false,
                RetryPolicy.NONE,
                List.of("system", "shell", "execution"),
                List.of("execute", "run")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            return ValidationResult.fail("command is required.");
        }

        String normalized = command.toLowerCase().trim();

        // 절대 차단 명령 체크
        for (String blocked : BLOCKED_COMMANDS) {
            if (normalized.contains(blocked.toLowerCase())) {
                return ValidationResult.fail(
                        "Blocked dangerous command: " + blocked);
            }
        }

        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String command = (String) input.get("command");
        long timeoutMs = getTimeout(input);
        String workingDir = (String) input.get("working_directory");

        // 위험 명령 경고 (차단은 아님)
        String normalized = command.toLowerCase().trim();
        String warning = null;
        for (String prefix : DANGEROUS_PREFIXES) {
            if (normalized.startsWith(prefix.toLowerCase())) {
                warning = "⚠ Potentially dangerous command detected: " + prefix;
                log.warn("Dangerous command executed by tenant {}: {}", ctx.tenantId(), command);
                break;
            }
        }

        // 작업 디렉토리 결정
        Path cwd;
        if (workingDir != null && !workingDir.isBlank()) {
            cwd = workspaceResolver.resolve(ctx, workingDir);
        } else {
            cwd = workspaceResolver.getWorkspaceRoot(ctx);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);

            // 환경 변수 정리: CLAUDECODE 제거 (재귀 방지)
            pb.environment().remove("CLAUDECODE");

            Process process = pb.start();

            // stdout/stderr 비동기 캡처
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream()));

            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return ToolResult.error("Command timed out after " + timeoutMs + "ms: " + truncate(command, 100))
                        .withDuration(System.currentTimeMillis() - start);
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            // 출력 크기 제한
            stdout = truncateOutput(stdout);
            stderr = truncateOutput(stderr);

            Map<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("exit_code", exitCode);
            output.put("stdout", stdout);
            if (!stderr.isEmpty()) {
                output.put("stderr", stderr);
            }
            if (warning != null) {
                output.put("warning", warning);
            }

            String summary = exitCode == 0
                    ? "Command completed (exit 0)" + (stdout.length() > 200 ? ", output truncated" : "")
                    : "Command failed (exit " + exitCode + ")" + (stderr.isEmpty() ? "" : ": " + truncate(stderr, 200));

            long duration = System.currentTimeMillis() - start;

            return new ToolResult(exitCode == 0, output, summary,
                    List.of(), // artifacts
                    List.of("bash_exec:" + truncate(command, 50)), // sideEffects
                    Map.of("command", truncate(command, 200), "exitCode", exitCode),
                    null, duration);

        } catch (IOException e) {
            return ToolResult.error("Failed to start process: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Process interrupted")
                    .withDuration(System.currentTimeMillis() - start);
        } catch (ExecutionException | TimeoutException e) {
            return ToolResult.error("Failed to capture output: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private long getTimeout(Map<String, Object> input) {
        Object timeoutObj = input.get("timeout_ms");
        if (timeoutObj instanceof Number n) {
            long val = n.longValue();
            return Math.min(Math.max(val, 1000), MAX_TIMEOUT_MS);
        }
        return DEFAULT_TIMEOUT_MS;
    }

    private String readStream(java.io.InputStream is) {
        try {
            byte[] bytes = is.readNBytes(MAX_OUTPUT_BYTES);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[stream read error: " + e.getMessage() + "]";
        }
    }

    private String truncateOutput(String s) {
        if (s.length() > MAX_OUTPUT_BYTES) {
            return s.substring(0, MAX_OUTPUT_BYTES) + "\n... [truncated]";
        }
        return s;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
