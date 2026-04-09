package com.platform.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.repository.ConnectionRepository;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * CR-037 PRD-244: 워크플로우 결과 기반 Git 커밋 + GitHub PR 자동 생성.
 * ClaudeCodeTool 경유 없이 ProcessBuilder(git) + GitHub REST API로 직접 실행.
 */
@Component
public class SuggestBackgroundPRTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SuggestBackgroundPRTool.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final WorkspaceResolver workspaceResolver;
    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SuggestBackgroundPRTool(WorkspaceResolver workspaceResolver,
                                   ConnectionRepository connectionRepository,
                                   ObjectMapper objectMapper) {
        this.workspaceResolver = workspaceResolver;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "suggest_background_pr",
                "Create a Git branch, commit changes, push, and open a GitHub Pull Request. " +
                        "Requires a GITHUB connection with a personal access token. " +
                        "Useful for automating code changes from workflows.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "PR title (max 256 chars)"),
                                "body", Map.of("type", "string",
                                        "description", "PR description/body (markdown supported)"),
                                "branch_name", Map.of("type", "string",
                                        "description", "Branch name to create (e.g., 'feature/auto-fix-123')"),
                                "base_branch", Map.of("type", "string",
                                        "description", "Base branch for the PR (default: main)"),
                                "commit_message", Map.of("type", "string",
                                        "description", "Git commit message"),
                                "file_patterns", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "File patterns to stage (default: ['.']). Use specific paths for safety."),
                                "draft", Map.of("type", "boolean", "default", false,
                                        "description", "Create as draft PR")
                        ),
                        "required", List.of("title", "branch_name", "commit_message")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "suggest_background_pr", "1.0", ToolScope.BUILTIN,
                PermissionLevel.FULL,
                false, false, true, false,
                RetryPolicy.NONE,
                List.of("git", "github", "pr", "automation"),
                List.of("write", "push", "create")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String title = (String) input.get("title");
        if (title == null || title.isBlank()) {
            return ValidationResult.fail("title is required.");
        }
        if (title.length() > 256) {
            return ValidationResult.fail("title too long (max 256 chars).");
        }
        String branchName = (String) input.get("branch_name");
        if (branchName == null || branchName.isBlank()) {
            return ValidationResult.fail("branch_name is required.");
        }
        // 브랜치명 검증: 알파벳, 숫자, -, _, / 만 허용
        if (!branchName.matches("[a-zA-Z0-9/_-]+")) {
            return ValidationResult.fail("branch_name contains invalid characters. Use alphanumeric, -, _, / only.");
        }
        String commitMessage = (String) input.get("commit_message");
        if (commitMessage == null || commitMessage.isBlank()) {
            return ValidationResult.fail("commit_message is required.");
        }
        return ValidationResult.OK;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String title = (String) input.get("title");
        String body = (String) input.getOrDefault("body", "");
        String branchName = (String) input.get("branch_name");
        String baseBranch = (String) input.getOrDefault("base_branch", "main");
        String commitMessage = (String) input.get("commit_message");
        List<String> filePatterns = (List<String>) input.getOrDefault("file_patterns", List.of("."));
        boolean draft = Boolean.TRUE.equals(input.get("draft"));

        Path workDir = workspaceResolver.getWorkspaceRoot(ctx);

        try {
            // 1. 현재 브랜치 저장
            String currentBranch = gitExec(workDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();

            // 2. 새 브랜치 생성 + 전환
            gitExec(workDir, "git", "checkout", "-b", branchName);

            // 3. 파일 스테이징
            for (String pattern : filePatterns) {
                gitExec(workDir, "git", "add", pattern);
            }

            // 4. 변경사항 확인
            String status = gitExec(workDir, "git", "status", "--porcelain");
            if (status.isBlank()) {
                // 원래 브랜치로 복귀
                gitExec(workDir, "git", "checkout", currentBranch);
                gitExec(workDir, "git", "branch", "-D", branchName);
                return ToolResult.error("No changes to commit.")
                        .withDuration(System.currentTimeMillis() - start);
            }

            // 5. 커밋
            gitExec(workDir, "git", "commit", "-m", commitMessage);

            // 6. 리모트 확인 + 푸시
            String remote = gitExec(workDir, "git", "remote").trim().split("\n")[0];
            if (remote.isBlank()) remote = "origin";
            gitExec(workDir, "git", "push", "-u", remote, branchName);

            // 7. GitHub API로 PR 생성
            String repoSlug = detectRepoSlug(workDir, remote);
            String githubToken = findGitHubToken();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("branch", branchName);
            result.put("base_branch", baseBranch);
            result.put("commit_message", commitMessage);

            if (githubToken != null && repoSlug != null) {
                Map<String, Object> prResult = createPullRequest(
                        repoSlug, title, body, branchName, baseBranch, draft, githubToken);
                result.putAll(prResult);
            } else {
                result.put("pr_created", false);
                result.put("reason", githubToken == null
                        ? "No GITHUB connection found with access token"
                        : "Could not detect repository slug from remote URL");
            }

            // 8. 원래 브랜치로 복귀
            gitExec(workDir, "git", "checkout", currentBranch);

            String summary = result.containsKey("pr_url")
                    ? "PR created: " + result.get("pr_url")
                    : "Branch pushed: " + branchName + " (PR creation skipped: " + result.get("reason") + ")";

            return new ToolResult(true, result, summary,
                    List.of(),
                    List.of("git_push:" + branchName, "pr_create:" + title),
                    Map.of("branch", branchName, "title", title),
                    null, System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.error("SuggestBackgroundPR failed: {}", e.getMessage(), e);
            // 실패 시 원래 브랜치로 복귀 시도
            try {
                gitExec(workDir, "git", "checkout", "-");
            } catch (Exception ignored) {}
            return ToolResult.error("PR creation failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private String gitExec(Path workDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readNBytes(1_048_576), StandardCharsets.UTF_8);

        boolean completed = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Git command timed out: " + String.join(" ", command));
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Git command failed (exit " + exitCode + "): " + output.trim());
        }

        return output;
    }

    private String detectRepoSlug(Path workDir, String remote) {
        try {
            String url = gitExec(workDir, "git", "remote", "get-url", remote).trim();
            // SSH: git@github.com:owner/repo.git or git@github-alias:owner/repo.git
            if (url.contains(":") && url.contains("/")) {
                String slug = url.substring(url.indexOf(":") + 1);
                if (slug.endsWith(".git")) slug = slug.substring(0, slug.length() - 4);
                return slug;
            }
            // HTTPS: https://github.com/owner/repo.git
            if (url.contains("github.com/")) {
                String slug = url.substring(url.indexOf("github.com/") + 11);
                if (slug.endsWith(".git")) slug = slug.substring(0, slug.length() - 4);
                return slug;
            }
        } catch (Exception e) {
            log.debug("Failed to detect repo slug: {}", e.getMessage());
        }
        return null;
    }

    private String findGitHubToken() {
        try {
            var connections = connectionRepository.findByType("GITHUB");
            for (var conn : connections) {
                if (conn.getConfig() != null) {
                    Object token = conn.getConfig().get("access_token");
                    if (token == null) token = conn.getConfig().get("api_key");
                    if (token instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find GitHub token: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> createPullRequest(String repoSlug, String title, String body,
                                                    String head, String base, boolean draft,
                                                    String token) throws IOException, InterruptedException {
        Map<String, Object> prBody = new LinkedHashMap<>();
        prBody.put("title", title);
        prBody.put("body", body);
        prBody.put("head", head);
        prBody.put("base", base);
        prBody.put("draft", draft);

        String json = objectMapper.writeValueAsString(prBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repoSlug + "/pulls"))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(HTTP_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, Object> result = new LinkedHashMap<>();
        if (response.statusCode() == 201) {
            JsonNode pr = objectMapper.readTree(response.body());
            result.put("pr_created", true);
            result.put("pr_number", pr.get("number").asInt());
            result.put("pr_url", pr.get("html_url").asText());
        } else {
            result.put("pr_created", false);
            result.put("reason", "GitHub API " + response.statusCode() + ": " +
                    truncate(response.body(), 200));
        }
        return result;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
