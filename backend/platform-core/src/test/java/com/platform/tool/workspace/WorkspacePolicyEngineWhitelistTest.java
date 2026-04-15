package com.platform.tool.workspace;

import com.platform.tool.ApprovalState;
import com.platform.tool.PermissionLevel;
import com.platform.tool.ToolContext;
import com.platform.tool.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CR-045: WorkspacePolicyEngine L2 독립 화이트리스트 체크 테스트.
 * resolver가 우회돼도 엔진이 막는지 검증.
 */
class WorkspacePolicyEngineWhitelistTest {

    @TempDir
    Path allowedRoot;

    @TempDir
    Path otherRoot;

    private ToolContext ctxWith(String workspacePath) {
        return new ToolContext(
                "t1", null, null, "s1", null, null, null,
                PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                workspacePath, false, 0
        );
    }

    @Test
    void deniesPathOutsideWhitelist_evenIfResolverAllows() throws IOException {
        // resolver는 화이트리스트 없이 생성 (L1 우회 상황 모사)
        WorkspaceResolver permissive = new WorkspaceResolver(otherRoot.toString(), List.of());
        // engine은 화이트리스트 주입
        WorkspacePolicyEngine engine = new WorkspacePolicyEngine(permissive, List.of(allowedRoot));

        Files.writeString(otherRoot.resolve("leak.txt"), "leak");
        ToolContext ctx = ctxWith(otherRoot.toString());

        ValidationResult result = engine.validatePath(
                ctx, WorkspacePolicy.defaultPolicy(), otherRoot.resolve("leak.txt").toString());

        assertFalse(result.valid());
        assertEquals(90, result.errorCode());
    }

    @Test
    void allowsPathInsideWhitelist() throws IOException {
        WorkspaceResolver resolver = new WorkspaceResolver(allowedRoot.toString(), List.of(allowedRoot));
        WorkspacePolicyEngine engine = new WorkspacePolicyEngine(resolver, List.of(allowedRoot));

        Files.writeString(allowedRoot.resolve("ok.txt"), "ok");
        ToolContext ctx = ctxWith(allowedRoot.toString());

        ValidationResult result = engine.validatePath(
                ctx, WorkspacePolicy.defaultPolicy(), "ok.txt");

        assertTrue(result.valid(), () -> "expected valid, got: " + result.message());
    }

    @Test
    void emptyWhitelist_keepsBackwardCompatibility() throws IOException {
        WorkspaceResolver resolver = new WorkspaceResolver(allowedRoot.toString(), List.of());
        WorkspacePolicyEngine engine = new WorkspacePolicyEngine(resolver);  // 기존 2-인자 생성자

        Files.writeString(allowedRoot.resolve("ok.txt"), "ok");
        ToolContext ctx = ctxWith(allowedRoot.toString());

        ValidationResult result = engine.validatePath(
                ctx, WorkspacePolicy.defaultPolicy(), "ok.txt");

        assertTrue(result.valid());
    }
}
