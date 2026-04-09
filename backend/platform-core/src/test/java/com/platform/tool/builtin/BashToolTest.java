package com.platform.tool.builtin;

import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CR-037 PRD-241: BashTool 단위 테스트.
 */
class BashToolTest {

    @TempDir
    Path tempDir;

    private ToolContext ctx;
    private BashTool bashTool;

    @BeforeEach
    void setUp() throws IOException {
        var resolver = new WorkspaceResolver();
        bashTool = new BashTool(resolver);

        Files.writeString(tempDir.resolve("test.txt"), "hello world\n");

        ctx = new ToolContext(
                "test-tenant", null, null, "test-session",
                null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                tempDir.toString(), false, 0
        );
    }

    // --- 기본 실행 ---

    @Test
    void execute_echoCommand_success() {
        ToolResult result = bashTool.execute(Map.of("command", "echo hello"), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertEquals(0, output.get("exit_code"));
        assertTrue(((String) output.get("stdout")).contains("hello"));
    }

    @Test
    void execute_catFile_success() {
        ToolResult result = bashTool.execute(
                Map.of("command", "cat test.txt", "working_directory", tempDir.toString()), ctx);

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue(((String) output.get("stdout")).contains("hello world"));
    }

    @Test
    void execute_failingCommand_returnsError() {
        ToolResult result = bashTool.execute(Map.of("command", "false"), ctx);

        assertFalse(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertNotEquals(0, output.get("exit_code"));
    }

    @Test
    void execute_stderrCapture() {
        ToolResult result = bashTool.execute(Map.of("command", "echo error >&2"), ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue(((String) output.get("stderr")).contains("error"));
    }

    // --- 위험 명령 차단 ---

    @Test
    void validate_blockedCommand_rmRf() {
        ValidationResult v = bashTool.validateInput(Map.of("command", "rm -rf /"), ctx);
        assertFalse(v.valid());
        assertTrue(v.message().contains("Blocked"));
    }

    @Test
    void validate_blockedCommand_mkfs() {
        ValidationResult v = bashTool.validateInput(Map.of("command", "mkfs /dev/sda"), ctx);
        assertFalse(v.valid());
    }

    @Test
    void validate_blockedCommand_shutdown() {
        ValidationResult v = bashTool.validateInput(Map.of("command", "shutdown -h now"), ctx);
        assertFalse(v.valid());
    }

    @Test
    void validate_safeCommand_passes() {
        ValidationResult v = bashTool.validateInput(Map.of("command", "ls -la"), ctx);
        assertTrue(v.valid());
    }

    @Test
    void validate_emptyCommand_fails() {
        ValidationResult v = bashTool.validateInput(Map.of("command", ""), ctx);
        assertFalse(v.valid());
    }

    @Test
    void validate_missingCommand_fails() {
        ValidationResult v = bashTool.validateInput(Map.of(), ctx);
        assertFalse(v.valid());
    }

    // --- 타임아웃 ---

    @Test
    void execute_timeout_returnsError() {
        ToolResult result = bashTool.execute(
                Map.of("command", "sleep 10", "timeout_ms", 1000), ctx);

        assertFalse(result.success());
        assertTrue(result.summary().contains("timed out"));
    }

    // --- ToolContractMeta ---

    @Test
    void contractMeta_isCorrect() {
        ToolContractMeta meta = bashTool.getContractMeta();
        assertEquals("bash", meta.id());
        assertEquals(PermissionLevel.FULL, meta.permissionLevel());
        assertTrue(meta.destructive());
        assertFalse(meta.readOnly());
        assertEquals(ToolScope.BUILTIN, meta.scope());
    }

    @Test
    void definition_nameIsBash() {
        assertEquals("bash", bashTool.getDefinition().name());
    }
}
