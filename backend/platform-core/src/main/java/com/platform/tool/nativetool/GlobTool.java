package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * CR-029 (PRD-171): 파일 집합을 glob 패턴으로 검색.
 * 100개 제한, mtime 내림차순 정렬.
 */
@Component
public class GlobTool implements EnhancedToolExecutor {

    private static final int MAX_RESULTS = 100;

    private final WorkspaceResolver workspaceResolver;

    public GlobTool(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_glob",
                "Fast file pattern matching. Returns matching file paths sorted by modification time.\n\n- Supports glob patterns like **/*.java or src/**/*.ts\n- Results are sorted by modification time (newest first)\n- Returns up to 100 matches\n- Use this tool when you need to find files by name pattern\n- Pass the returned file paths directly to builtin_file_read",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pattern", Map.of("type", "string", "description", "Glob pattern (e.g., **/*.java)"),
                                "path", Map.of("type", "string", "description", "Base directory (default: workspace root)")
                        ),
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin_glob", List.of("filesystem", "search"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String pattern = (String) input.get("pattern");
        String basePath = (String) input.getOrDefault("path", null);

        Path searchRoot = basePath != null
                ? workspaceResolver.resolve(ctx, basePath)
                : workspaceResolver.getWorkspaceRoot(ctx);

        if (!Files.isDirectory(searchRoot)) {
            return ToolResult.error("Directory not found: " + searchRoot)
                    .withDuration(System.currentTimeMillis() - start);
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> matches = new ArrayList<>();
            boolean[] truncated = {false};

            Files.walkFileTree(searchRoot, EnumSet.noneOf(FileVisitOption.class), 20, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= MAX_RESULTS) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = searchRoot.relativize(file);
                    if (matcher.matches(relative)) {
                        matches.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.equals(".git") || name.equals("node_modules") || name.equals(".svn")
                            || name.equals("build") || name.equals("target") || name.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            // mtime 내림차순 정렬
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });

            List<String> filenames = matches.stream()
                    .map(p -> searchRoot.relativize(p).toString())
                    .toList();

            long durationMs = System.currentTimeMillis() - start;
            Map<String, Object> output = Map.of(
                    "filenames", filenames,
                    "numFiles", filenames.size(),
                    "durationMs", durationMs,
                    "truncated", truncated[0]
            );

            String summary = truncated[0]
                    ? String.format("glob '%s': %d files (최대 %d개 제한)", pattern, filenames.size(), MAX_RESULTS)
                    : String.format("glob '%s': %d files", pattern, filenames.size());

            return new ToolResult(true, output, summary,
                    List.of(), List.of(),
                    Map.of("pattern", pattern, "numFiles", filenames.size()),
                    null, durationMs);

        } catch (IOException e) {
            return ToolResult.error("Glob search failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }
}
