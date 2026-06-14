package com.platform.tool.nativetool;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspacePolicy;
import com.platform.tool.workspace.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * URL 에서 파일을 내려받아 워크스페이스에 그대로(바이너리) 저장하는 도구.
 *
 * <p>{@code file_write} 는 텍스트 전용이고 {@code parse_document} 는 다운로드 후
 * 텍스트로 변환해 원본 바이트를 돌려주지 않는다. 이 도구는 "URL → 워크스페이스 파일"
 * 의 순수 다운로드+저장만 담당한다.</p>
 *
 * <p>경로 검증은 {@link WorkspacePolicyEngine#validatePath} (L1 resolver + L2 화이트리스트)
 * 를 file_write 와 동일하게 재사용한다. 다운로드 바이트 크기는 {@link #MAX_CONTENT_SIZE}
 * 로 자체 제한한다 (policy.maxFileSize 는 validateContent 전용이라 여기선 미적용).</p>
 */
public class DownloadFileTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DownloadFileTool.class);
    private static final long MAX_CONTENT_SIZE = 50L * 1024 * 1024; // 50MB
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final WorkspaceResolver workspaceResolver;
    private final WorkspacePolicyEngine policyEngine;

    public DownloadFileTool(WorkspaceResolver workspaceResolver, WorkspacePolicyEngine policyEngine) {
        this.workspaceResolver = workspaceResolver;
        this.policyEngine = policyEngine;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "download_file",
                "Download a file from an HTTP/HTTPS URL and save it to the workspace as-is " +
                        "(binary-safe). Creates parent directories automatically. " +
                        "If the target file exists, set overwrite=true to replace it. " +
                        "Use parse_document instead if you want the file's text content rather than the raw file.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string",
                                        "description", "Source URL to download (http or https only)"),
                                "file_path", Map.of("type", "string",
                                        "description", "Destination path (absolute or relative to workspace)"),
                                "overwrite", Map.of("type", "boolean", "default", false,
                                        "description", "Allow overwriting an existing file (default: false)")
                        ),
                        "required", List.of("url", "file_path")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "download_file", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("network", "filesystem", "download"),
                List.of("download", "write", "create")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String url = (String) input.get("url");
        if (url == null || url.isBlank()) {
            return ValidationResult.fail("url is required.");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return ValidationResult.fail("url must use http or https scheme.");
            }
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("Invalid url: " + e.getMessage());
        }

        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ValidationResult.fail("file_path is required.");
        }
        return policyEngine.validatePath(ctx, WorkspacePolicy.defaultPolicy(), filePath);
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String url = (String) input.get("url");
        String filePath = (String) input.get("file_path");
        boolean overwrite = Boolean.TRUE.equals(input.get("overwrite"));

        Path resolved = workspaceResolver.resolve(ctx, filePath);

        if (Files.exists(resolved) && !overwrite) {
            return ToolResult.error(
                            "File already exists: " + filePath + ". Set overwrite=true to replace it.")
                    .withDuration(System.currentTimeMillis() - start);
        }

        try {
            byte[] bytes = downloadBytes(url);
            if (bytes.length > MAX_CONTENT_SIZE) {
                return ToolResult.error("Downloaded file too large: " + bytes.length
                                + " bytes (max " + MAX_CONTENT_SIZE + ").")
                        .withDuration(System.currentTimeMillis() - start);
            }

            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            boolean existed = Files.exists(resolved);
            Files.write(resolved, bytes);

            String relativePath = workspaceResolver.relativize(ctx, resolved);

            Map<String, Object> output = Map.of(
                    "file_path", relativePath,
                    "bytes_written", bytes.length,
                    "source_url", url,
                    "created", !existed,
                    "overwritten", existed
            );

            String summary = (existed ? "Overwritten" : "Downloaded") + ": " + relativePath
                    + " (" + bytes.length + " bytes from " + url + ")";

            return new ToolResult(true, output, summary,
                    List.of(new ToolArtifact("file", relativePath, Map.of("bytes", bytes.length))),
                    List.of("download_file:" + relativePath),
                    Map.of("file_path", relativePath, "bytes", bytes.length,
                            "source_url", url, "overwritten", existed),
                    null, System.currentTimeMillis() - start);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("DownloadFileTool failed for {} -> {}: {}", url, filePath, e.getMessage());
            return ToolResult.error("Download failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private byte[] downloadBytes(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("download failed: HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }
}
