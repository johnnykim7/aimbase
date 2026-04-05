package com.platform.tool.workspace;

import com.platform.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CR-029: WorkspacePolicyEngine 단위 테스트.
 */
class WorkspacePolicyEngineTest {

    @TempDir
    Path tempDir;

    private WorkspaceResolver resolver;
    private WorkspacePolicyEngine engine;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        resolver = new WorkspaceResolver();
        engine = new WorkspacePolicyEngine(resolver);

        ctx = new ToolContext(
                "test-tenant", null, null, "test-session",
                null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                tempDir.toString(), false, 0
        );

        Files.writeString(tempDir.resolve("safe.txt"), "safe content");
        Files.writeString(tempDir.resolve("secret.env"), "API_KEY='sk-123abc'");
        Files.write(tempDir.resolve("binary.bin"), new byte[]{0, 1, 2, 0, 3});
    }

    @Test
    void validatePath_allowedFile() {
        var policy = WorkspacePolicy.defaultPolicy();
        ValidationResult result = engine.validatePath(ctx, policy, "safe.txt");
        assertTrue(result.valid());
    }

    @Test
    void validatePath_pathTraversal() {
        var policy = WorkspacePolicy.defaultPolicy();
        ValidationResult result = engine.validatePath(ctx, policy, "../../etc/passwd");
        assertFalse(result.valid());
        assertEquals(100, result.errorCode());
    }

    @Test
    void validatePath_deniedExtension() {
        var policy = new WorkspacePolicy(
                List.of(), List.of(), List.of(), List.of("exe", "bin"),
                10_000_000, true, List.of()
        );
        ValidationResult result = engine.validatePath(ctx, policy, "binary.bin");
        assertFalse(result.valid());
        assertEquals(103, result.errorCode());
    }

    @Test
    void validateContent_secretPattern() throws IOException {
        var policy = WorkspacePolicy.defaultPolicy();
        ValidationResult result = engine.validateContent(policy, tempDir.resolve("secret.env"));
        assertFalse(result.valid());
        assertEquals(202, result.errorCode());
    }

    @Test
    void validateContent_binaryFile() {
        var policy = WorkspacePolicy.defaultPolicy();
        ValidationResult result = engine.validateContent(policy, tempDir.resolve("binary.bin"));
        assertFalse(result.valid());
        assertEquals(201, result.errorCode());
    }

    @Test
    void validateContent_safeFile() {
        var policy = WorkspacePolicy.defaultPolicy();
        ValidationResult result = engine.validateContent(policy, tempDir.resolve("safe.txt"));
        assertTrue(result.valid());
    }

    @Test
    void validateContent_fileSizeExceeded() throws IOException {
        // 1KB 제한 정책
        var policy = new WorkspacePolicy(
                List.of(), List.of(), List.of(), List.of(),
                1024, false, List.of()
        );
        // 2KB 파일 생성
        Files.writeString(tempDir.resolve("big.txt"), "x".repeat(2048));
        ValidationResult result = engine.validateContent(policy, tempDir.resolve("big.txt"));
        assertFalse(result.valid());
        assertEquals(200, result.errorCode());
    }
}
