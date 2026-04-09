package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-029 (PRD-174): 파일/디렉토리의 존재, 타입, 크기, mtime 등 확인.
 * 읽기 전 검증용 경량 도구.
 */
@Component
public class PathInfoTool implements EnhancedToolExecutor {

    private final WorkspaceResolver workspaceResolver;

    public PathInfoTool(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_path_info",
                "Checks file/directory existence, type, size, and modification time. Use before reading a file to verify it exists.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Path to check")
                        ),
                        "required", List.of("path")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin_path_info", List.of("filesystem", "metadata"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String pathStr = (String) input.get("path");
        Path resolved = workspaceResolver.resolve(ctx, pathStr);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("path", pathStr);
        output.put("exists", Files.exists(resolved));

        if (!Files.exists(resolved)) {
            output.put("isFile", false);
            output.put("isDirectory", false);
            return new ToolResult(true, output, "Path not found: " + pathStr,
                    List.of(), List.of(), Map.of(), null, System.currentTimeMillis() - start);
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(resolved, BasicFileAttributes.class);
            output.put("isFile", attrs.isRegularFile());
            output.put("isDirectory", attrs.isDirectory());
            output.put("size", attrs.size());
            output.put("mtime", attrs.lastModifiedTime().toString());
            output.put("readable", Files.isReadable(resolved));
            output.put("writable", Files.isWritable(resolved));

            String name = resolved.getFileName().toString();
            int dot = name.lastIndexOf('.');
            output.put("extension", dot > 0 ? name.substring(dot + 1).toLowerCase() : "");

            String mimeType = Files.probeContentType(resolved);
            output.put("mimeType", mimeType != null ? mimeType : "unknown");

            String summary = attrs.isDirectory()
                    ? String.format("Directory: %s", pathStr)
                    : String.format("File: %s (%d bytes)", pathStr, attrs.size());

            return new ToolResult(true, output, summary,
                    List.of(), List.of(), Map.of("path", pathStr),
                    null, System.currentTimeMillis() - start);

        } catch (IOException e) {
            return ToolResult.error("경로 정보 조��� 실패: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }
}
