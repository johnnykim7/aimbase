package com.platform.tool.nativetool;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspacePolicy;
import com.platform.tool.workspace.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * CR-037 PRD-242: 신규 파일 생성 도구.
 * SafeEditTool은 기존 파일 편집만 지원하므로, 새 파일 생성은 이 도구가 담당.
 * 기존 파일 덮어쓰기 시 overwrite=true 필수.
 */
public class FileWriteTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);
    private static final long MAX_CONTENT_SIZE = 10_485_760; // 10MB

    private final WorkspaceResolver workspaceResolver;
    private final WorkspacePolicyEngine policyEngine;

    public FileWriteTool(WorkspaceResolver workspaceResolver, WorkspacePolicyEngine policyEngine) {
        this.workspaceResolver = workspaceResolver;
        this.policyEngine = policyEngine;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "file_write",
                "Write content to a new file. Creates parent directories automatically. " +
                        "If the file already exists, set overwrite=true to replace it. " +
                        "For editing existing files, use builtin_safe_edit instead.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string",
                                        "description", "File path to write (absolute or relative to workspace)"),
                                "content", Map.of("type", "string",
                                        "description", "File content to write"),
                                "overwrite", Map.of("type", "boolean", "default", false,
                                        "description", "Allow overwriting existing files (default: false)")
                        ),
                        "required", List.of("file_path", "content")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "file_write", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("filesystem", "write"),
                List.of("write", "create")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ValidationResult.fail("file_path is required.");
        }
        String content = (String) input.get("content");
        if (content == null) {
            return ValidationResult.fail("content is required.");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_SIZE) {
            return ValidationResult.fail("Content exceeds maximum size of 10MB.");
        }

        return policyEngine.validatePath(ctx, WorkspacePolicy.defaultPolicy(), filePath);
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");
        boolean overwrite = Boolean.TRUE.equals(input.get("overwrite"));

        Path resolved = workspaceResolver.resolve(ctx, filePath);

        // 기존 파일 존재 체크
        if (Files.exists(resolved) && !overwrite) {
            return ToolResult.error(
                    "File already exists: " + filePath + ". Set overwrite=true to replace it.")
                    .withDuration(System.currentTimeMillis() - start);
        }

        try {
            // 부모 디렉토리 자동 생성
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            boolean existed = Files.exists(resolved);
            Files.writeString(resolved, content, StandardCharsets.UTF_8);

            long bytes = content.getBytes(StandardCharsets.UTF_8).length;
            String relativePath = workspaceResolver.relativize(ctx, resolved);

            Map<String, Object> output = Map.of(
                    "file_path", relativePath,
                    "bytes_written", bytes,
                    "created", !existed,
                    "overwritten", existed
            );

            String summary = (existed ? "Overwritten" : "Created") + ": " + relativePath
                    + " (" + bytes + " bytes)";

            return new ToolResult(true, output, summary,
                    List.of(new ToolArtifact("file", relativePath, Map.of("bytes", bytes))),
                    List.of("file_write:" + relativePath),
                    Map.of("file_path", relativePath, "bytes", bytes, "overwritten", existed),
                    null, System.currentTimeMillis() - start);

        } catch (IOException e) {
            log.error("FileWriteTool failed for {}: {}", filePath, e.getMessage());
            return ToolResult.error("File write failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }
}
