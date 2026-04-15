package com.platform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CR-045: WorkspaceProperties 화이트리스트 판정 테스트.
 */
class WorkspacePropertiesTest {

    @TempDir
    Path root;

    @TempDir
    Path other;

    private WorkspaceProperties props(List<String> roots) {
        WorkspaceProperties p = new WorkspaceProperties();
        p.setWhitelistRoots(roots);
        return p;
    }

    @Test
    void emptyWhitelist_alwaysInside() {
        assertTrue(props(List.of()).isInsideWhitelist(Path.of("/any/path")));
    }

    @Test
    void insideWhitelist_true() {
        WorkspaceProperties p = props(List.of(root.toString()));
        assertTrue(p.isInsideWhitelist(root.resolve("sub")));
    }

    @Test
    void outsideWhitelist_false() {
        WorkspaceProperties p = props(List.of(root.toString()));
        assertFalse(p.isInsideWhitelist(other.resolve("foo")));
    }

    @Test
    void homeExpansion_works() {
        WorkspaceProperties p = props(List.of("~/"));
        Path inside = Path.of(System.getProperty("user.home"), "anything");
        assertTrue(p.isInsideWhitelist(inside));
    }
}
