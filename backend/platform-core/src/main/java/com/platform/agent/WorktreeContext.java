package com.platform.agent;

/**
 * CR-030 PRD-208: Git Worktree 격리 환경 정보.
 *
 * @param worktreePath  worktree 디렉토리 절대 경로
 * @param branchName    worktree 전용 브랜치명
 * @param baseCommit    기준 커밋 해시
 */
public record WorktreeContext(
        String worktreePath,
        String branchName,
        String baseCommit
) {
    public WorktreeContext {
        if (worktreePath == null || worktreePath.isBlank()) {
            throw new IllegalArgumentException("worktreePath is required");
        }
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("branchName is required");
        }
    }
}
