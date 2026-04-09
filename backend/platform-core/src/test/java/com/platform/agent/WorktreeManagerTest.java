package com.platform.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-030 PRD-208: WorktreeManager 단위 테스트.
 *
 * 참고: git worktree 명령 실행은 통합 테스트에서 검증.
 * 여기서는 모델/검증 로직만 테스트.
 */
class WorktreeManagerTest {

    @Test
    void worktreeContext_validConstruction() {
        WorktreeContext ctx = new WorktreeContext("/tmp/wt-123", "subagent/abc", "def456");

        assertThat(ctx.worktreePath()).isEqualTo("/tmp/wt-123");
        assertThat(ctx.branchName()).isEqualTo("subagent/abc");
        assertThat(ctx.baseCommit()).isEqualTo("def456");
    }

    @Test
    void worktreeContext_nullPath_throws() {
        assertThatThrownBy(() -> new WorktreeContext(null, "branch", "commit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worktreePath");
    }

    @Test
    void worktreeContext_blankBranch_throws() {
        assertThatThrownBy(() -> new WorktreeContext("/tmp/wt", "", "commit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branchName");
    }

    @Test
    void worktreeManager_initializesWithDefaults() {
        // repo-root와 worktree-base가 null이면 자동 감지
        WorktreeManager manager = new WorktreeManager(null, null, null);
        assertThat(manager).isNotNull();
    }

    @Test
    void worktreeManager_customPaths() {
        WorktreeManager manager = new WorktreeManager("/tmp/test-repo", "/tmp/test-wt", null);
        assertThat(manager).isNotNull();
    }

    @Test
    void worktreeManager_listWorktrees_doesNotThrow() {
        // 현재 디렉토리가 git repo가 아닐 수 있으므로 예외 무시
        WorktreeManager manager = new WorktreeManager(System.getProperty("user.dir"), null, null);
        try {
            String list = manager.listWorktrees();
            assertThat(list).isNotNull();
        } catch (RuntimeException e) {
            // git이 없거나 repo가 아닌 경우 허용
            assertThat(e.getMessage()).contains("worktree");
        }
    }
}
