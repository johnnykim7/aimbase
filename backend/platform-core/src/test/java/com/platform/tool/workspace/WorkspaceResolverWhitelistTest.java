package com.platform.tool.workspace;

import com.platform.tool.ApprovalState;
import com.platform.tool.PermissionLevel;
import com.platform.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CR-045: WorkspaceResolver 화이트리스트 클램프 단위 테스트.
 */
class WorkspaceResolverWhitelistTest {

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
    void emptyWhitelist_keepsBackwardCompatibility() {
        WorkspaceResolver resolver = new WorkspaceResolver("/data/workspaces", List.of());
        Path root = resolver.getWorkspaceRoot(ctxWith("/"));
        assertEquals(Path.of("/"), root);
    }

    @Test
    void insideWhitelist_returnsNormalizedPath() {
        Path sub = allowedRoot.resolve("project-a");
        WorkspaceResolver resolver = new WorkspaceResolver("/data/workspaces", List.of(allowedRoot));
        Path root = resolver.getWorkspaceRoot(ctxWith(sub.toString()));
        assertEquals(sub.toAbsolutePath().normalize(), root);
    }

    @Test
    void outsideWhitelist_throws() {
        WorkspaceResolver resolver = new WorkspaceResolver("/data/workspaces", List.of(allowedRoot));
        assertThrows(WorkspaceAccessException.class,
                () -> resolver.getWorkspaceRoot(ctxWith(otherRoot.toString())));
    }

    @Test
    void rootSlash_outsideWhitelist_throws() {
        WorkspaceResolver resolver = new WorkspaceResolver("/data/workspaces", List.of(allowedRoot));
        assertThrows(WorkspaceAccessException.class,
                () -> resolver.getWorkspaceRoot(ctxWith("/")));
    }

    @Test
    void fallbackPath_outsideWhitelist_throws() {
        // workspacePath=null → base/tenant/project 폴백 → 화이트리스트 밖 → throws
        WorkspaceResolver resolver = new WorkspaceResolver("/data/workspaces", List.of(allowedRoot));
        assertThrows(WorkspaceAccessException.class,
                () -> resolver.getWorkspaceRoot(ctxWith(null)));
    }
}
