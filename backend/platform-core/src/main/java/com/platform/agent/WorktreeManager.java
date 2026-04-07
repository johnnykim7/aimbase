package com.platform.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * CR-030 PRD-208: Git Worktree 기반 격리 실행 환경.
 *
 * - ProcessBuilder로 `git worktree add/remove` 실행
 * - WorktreeContext(worktreePath, branchName, baseCommit) 반환
 * - 에이전트 완료 후 변경 없으면 자동 정리
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    private final String repoRoot;
    private final String worktreeBase;

    public WorktreeManager(
            @Value("${platform.agent.repo-root:#{null}}") String repoRoot,
            @Value("${platform.agent.worktree-base:#{null}}") String worktreeBase) {
        this.repoRoot = repoRoot != null ? repoRoot : detectRepoRoot();
        this.worktreeBase = worktreeBase != null ? worktreeBase
                : Path.of(this.repoRoot, ".worktrees").toString();
    }

    /**
     * 새 worktree 생성.
     *
     * @param runId 서브에이전트 실행 ID (브랜치명에 포함)
     * @return WorktreeContext
     */
    public WorktreeContext create(String runId) {
        String branchName = "subagent/" + runId;
        String baseCommit = getCurrentCommit();
        Path worktreePath = Path.of(worktreeBase, runId);

        try {
            Files.createDirectories(worktreePath.getParent());

            // git worktree add <path> -b <branch>
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "worktree", "add", worktreePath.toString(), "-b", branchName)
                    .directory(new File(repoRoot))
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output = readOutput(process);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("git worktree add failed (exit " + exitCode + "): " + output);
            }

            log.info("Worktree created: path={}, branch={}, base={}", worktreePath, branchName, baseCommit);
            return new WorktreeContext(worktreePath.toString(), branchName, baseCommit);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to create worktree for runId=" + runId, e);
        }
    }

    /**
     * worktree에 변경사항이 있는지 확인.
     */
    public boolean hasChanges(WorktreeContext ctx) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(new File(ctx.worktreePath()))
                    .redirectErrorStream(true);
            Process process = pb.start();
            String output = readOutput(process);
            process.waitFor();
            return !output.isBlank();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Failed to check worktree changes: {}", ctx.worktreePath(), e);
            return false;
        }
    }

    /**
     * worktree 제거.
     */
    public void remove(WorktreeContext ctx) {
        try {
            // git worktree remove <path> --force
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "worktree", "remove", ctx.worktreePath(), "--force")
                    .directory(new File(repoRoot))
                    .redirectErrorStream(true);
            Process process = pb.start();
            String output = readOutput(process);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.warn("git worktree remove failed (exit {}): {}", exitCode, output);
            }

            // 임시 브랜치 삭제
            deleteBranch(ctx.branchName());

            log.info("Worktree removed: path={}, branch={}", ctx.worktreePath(), ctx.branchName());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to remove worktree: {}", ctx.worktreePath(), e);
        }
    }

    /**
     * worktree 목록 조회.
     */
    public String listWorktrees() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "list")
                    .directory(new File(repoRoot))
                    .redirectErrorStream(true);
            Process process = pb.start();
            String output = readOutput(process);
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to list worktrees", e);
        }
    }

    // ── 내부 헬퍼 ──

    private String getCurrentCommit() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(new File(repoRoot))
                    .redirectErrorStream(true);
            Process process = pb.start();
            String output = readOutput(process).trim();
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private void deleteBranch(String branchName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "branch", "-D", branchName)
                    .directory(new File(repoRoot))
                    .redirectErrorStream(true);
            Process process = pb.start();
            readOutput(process);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Failed to delete branch: {}", branchName);
        }
    }

    private String readOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String detectRepoRoot() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String root = reader.lines().collect(Collectors.joining()).trim();
                process.waitFor();
                return root.isEmpty() ? System.getProperty("user.dir") : root;
            }
        } catch (Exception e) {
            return System.getProperty("user.dir");
        }
    }
}
