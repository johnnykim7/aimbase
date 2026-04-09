package com.platform.tool.nativetool;

import com.platform.tool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
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
 * CR-037 PRD-242: FileWriteTool 단위 테스트.
 */
class FileWriteToolTest {

    @TempDir
    Path tempDir;

    private ToolContext ctx;
    private FileWriteTool fileWriteTool;

    @BeforeEach
    void setUp() {
        var resolver = new WorkspaceResolver();
        var policyEngine = new WorkspacePolicyEngine(resolver);
        fileWriteTool = new FileWriteTool(resolver, policyEngine);

        ctx = new ToolContext(
                "test-tenant", null, null, "test-session",
                null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                tempDir.toString(), false, 0
        );
    }

    // --- 신규 파일 생성 ---

    @Test
    void execute_createNewFile_success() throws IOException {
        ToolResult result = fileWriteTool.execute(Map.of(
                "file_path", "new-file.txt",
                "content", "hello world"
        ), ctx);

        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("new-file.txt")));
        assertEquals("hello world", Files.readString(tempDir.resolve("new-file.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertTrue((Boolean) output.get("created"));
        assertFalse((Boolean) output.get("overwritten"));
        assertEquals(11L, output.get("bytes_written"));
    }

    @Test
    void execute_createWithSubdirectories_success() throws IOException {
        ToolResult result = fileWriteTool.execute(Map.of(
                "file_path", "a/b/c/deep.txt",
                "content", "deep content"
        ), ctx);

        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("a/b/c/deep.txt")));
        assertEquals("deep content", Files.readString(tempDir.resolve("a/b/c/deep.txt")));
    }

    // --- 기존 파일 덮어쓰기 ---

    @Test
    void execute_existingFile_noOverwrite_fails() throws IOException {
        Files.writeString(tempDir.resolve("existing.txt"), "original");

        ToolResult result = fileWriteTool.execute(Map.of(
                "file_path", "existing.txt",
                "content", "replaced"
        ), ctx);

        assertFalse(result.success());
        assertTrue(result.summary().contains("already exists"));
        assertEquals("original", Files.readString(tempDir.resolve("existing.txt")));
    }

    @Test
    void execute_existingFile_withOverwrite_success() throws IOException {
        Files.writeString(tempDir.resolve("existing.txt"), "original");

        ToolResult result = fileWriteTool.execute(Map.of(
                "file_path", "existing.txt",
                "content", "replaced",
                "overwrite", true
        ), ctx);

        assertTrue(result.success());
        assertEquals("replaced", Files.readString(tempDir.resolve("existing.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertFalse((Boolean) output.get("created"));
        assertTrue((Boolean) output.get("overwritten"));
    }

    // --- 입력 검증 ---

    @Test
    void validate_missingFilePath_fails() {
        ValidationResult v = fileWriteTool.validateInput(Map.of("content", "x"), ctx);
        assertFalse(v.valid());
    }

    @Test
    void validate_missingContent_fails() {
        ValidationResult v = fileWriteTool.validateInput(Map.of("file_path", "x.txt"), ctx);
        assertFalse(v.valid());
    }

    @Test
    void validate_validInput_passes() throws IOException {
        // 워크스페이스 내 기존 파일로 검증 (toRealPath() 호환)
        Files.writeString(tempDir.resolve("validate-test.txt"), "");
        String absPath = tempDir.resolve("validate-test.txt").toRealPath().toString();
        ctx = new ToolContext(
                "test-tenant", null, null, "test-session",
                null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                tempDir.toRealPath().toString(), false, 0
        );
        ValidationResult v = fileWriteTool.validateInput(
                Map.of("file_path", absPath, "content", "hello"), ctx);
        assertTrue(v.valid(), "Validation failed: " + v.message());
    }

    // --- 절대 경로 ---

    @Test
    void execute_absolutePath_success() throws IOException {
        String absPath = tempDir.resolve("abs-test.txt").toString();

        ToolResult result = fileWriteTool.execute(Map.of(
                "file_path", absPath,
                "content", "absolute"
        ), ctx);

        assertTrue(result.success());
        assertEquals("absolute", Files.readString(tempDir.resolve("abs-test.txt")));
    }

    // --- ToolContractMeta ---

    @Test
    void contractMeta_isCorrect() {
        ToolContractMeta meta = fileWriteTool.getContractMeta();
        assertEquals("file_write", meta.id());
        assertEquals(PermissionLevel.RESTRICTED_WRITE, meta.permissionLevel());
        assertFalse(meta.readOnly());
        assertFalse(meta.destructive());
        assertEquals(ToolScope.NATIVE, meta.scope());
    }

    @Test
    void definition_nameIsFileWrite() {
        assertEquals("file_write", fileWriteTool.getDefinition().name());
    }
}
