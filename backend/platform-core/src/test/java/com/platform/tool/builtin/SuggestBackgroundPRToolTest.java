package com.platform.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.repository.ConnectionRepository;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CR-037 PRD-244: SuggestBackgroundPRTool 단위 테스트.
 * 실제 git/GitHub API 호출 없이 입력 검증 + 메타데이터 검증.
 */
@ExtendWith(MockitoExtension.class)
class SuggestBackgroundPRToolTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConnectionRepository connectionRepository;

    private ToolContext ctx;
    private SuggestBackgroundPRTool prTool;

    @BeforeEach
    void setUp() {
        var resolver = new WorkspaceResolver();
        prTool = new SuggestBackgroundPRTool(resolver, connectionRepository, new ObjectMapper());

        ctx = new ToolContext(
                "test-tenant", null, null, "test-session",
                null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                tempDir.toString(), false, 0
        );
    }

    // --- 입력 검증 ---

    @Test
    void validate_validInput_passes() {
        ValidationResult v = prTool.validateInput(Map.of(
                "title", "Fix: resolve null pointer",
                "branch_name", "fix/null-pointer-123",
                "commit_message", "fix NPE in UserService"
        ), ctx);
        assertTrue(v.valid());
    }

    @Test
    void validate_missingTitle_fails() {
        ValidationResult v = prTool.validateInput(Map.of(
                "branch_name", "fix/test",
                "commit_message", "test"
        ), ctx);
        assertFalse(v.valid());
        assertTrue(v.message().contains("title"));
    }

    @Test
    void validate_missingBranchName_fails() {
        ValidationResult v = prTool.validateInput(Map.of(
                "title", "Test PR",
                "commit_message", "test"
        ), ctx);
        assertFalse(v.valid());
        assertTrue(v.message().contains("branch_name"));
    }

    @Test
    void validate_missingCommitMessage_fails() {
        ValidationResult v = prTool.validateInput(Map.of(
                "title", "Test PR",
                "branch_name", "fix/test"
        ), ctx);
        assertFalse(v.valid());
        assertTrue(v.message().contains("commit_message"));
    }

    @Test
    void validate_invalidBranchName_fails() {
        ValidationResult v = prTool.validateInput(Map.of(
                "title", "Test PR",
                "branch_name", "fix/test with spaces",
                "commit_message", "test"
        ), ctx);
        assertFalse(v.valid());
        assertTrue(v.message().contains("invalid characters"));
    }

    @Test
    void validate_titleTooLong_fails() {
        String longTitle = "A".repeat(257);
        ValidationResult v = prTool.validateInput(Map.of(
                "title", longTitle,
                "branch_name", "fix/test",
                "commit_message", "test"
        ), ctx);
        assertFalse(v.valid());
        assertTrue(v.message().contains("too long"));
    }

    @Test
    void validate_branchNameWithSlash_passes() {
        ValidationResult v = prTool.validateInput(Map.of(
                "title", "Feature: add search",
                "branch_name", "feature/add-search-tool",
                "commit_message", "add search tool"
        ), ctx);
        assertTrue(v.valid());
    }

    // --- git 없는 디렉토리에서 실행 → 에러 ---

    @Test
    void execute_nonGitDir_fails() {
        ToolResult result = prTool.execute(Map.of(
                "title", "Test PR",
                "branch_name", "test/branch",
                "commit_message", "test commit"
        ), ctx);

        assertFalse(result.success());
        assertTrue(result.summary().contains("failed"));
    }

    // --- git init된 디렉토리에서 변경사항 없이 실행 → 에러 ---

    @Test
    void execute_noChanges_fails() throws IOException, InterruptedException {
        // git init
        ProcessBuilder pb = new ProcessBuilder("git", "init");
        pb.directory(tempDir.toFile());
        Process p = pb.start();
        p.waitFor();

        // git config (테스트용)
        new ProcessBuilder("git", "config", "user.email", "test@test.com")
                .directory(tempDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.name", "Test")
                .directory(tempDir.toFile()).start().waitFor();

        // 초기 커밋
        Files.writeString(tempDir.resolve("init.txt"), "init");
        new ProcessBuilder("git", "add", ".")
                .directory(tempDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "initial")
                .directory(tempDir.toFile()).start().waitFor();

        // 변경사항 없이 PR 시도
        ToolResult result = prTool.execute(Map.of(
                "title", "Empty PR",
                "branch_name", "test/empty",
                "commit_message", "no changes"
        ), ctx);

        assertFalse(result.success());
        assertTrue(result.summary().contains("No changes") || result.summary().contains("failed"));
    }

    // --- ToolContractMeta ---

    @Test
    void contractMeta_isCorrect() {
        ToolContractMeta meta = prTool.getContractMeta();
        assertEquals("suggest_background_pr", meta.id());
        assertEquals(PermissionLevel.FULL, meta.permissionLevel());
        assertTrue(meta.destructive());
        assertFalse(meta.readOnly());
        assertEquals(ToolScope.BUILTIN, meta.scope());
        assertTrue(meta.tags().contains("git"));
        assertTrue(meta.tags().contains("github"));
    }

    @Test
    void definition_nameIsSuggestBackgroundPr() {
        assertEquals("suggest_background_pr", prTool.getDefinition().name());
    }
}
