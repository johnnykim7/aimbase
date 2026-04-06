package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * CR-029 (PRD-172): ripgrep(rg) 우선 + Java regex fallback.
 * VCS 디렉토리 자동 제외.
 */
@Component
public class GrepTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);
    private static final int DEFAULT_HEAD_LIMIT = 250;

    private final WorkspaceResolver workspaceResolver;

    public GrepTool(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_grep",
                "Searches file contents using regex patterns. ALWAYS use this tool for text search.\n\nUsage:\n- Supports full regex syntax (예: \"log.*Error\", \"function\\\\s+\\\\w+\")\n- Filter files with glob parameter (예: \"*.java\", \"**/*.tsx\")\n- output_mode: \"content\" shows matching lines, \"files_with_matches\" shows file paths only, \"count\" shows match counts\n- Pass returned file paths directly to builtin_file_read\n- Case insensitive search: case_insensitive=true",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pattern", Map.of("type", "string", "description", "Regex pattern"),
                                "path", Map.of("type", "string", "description", "Search path (default: workspace root)"),
                                "glob", Map.of("type", "string", "description", "File filter glob (e.g., *.java)"),
                                "output_mode", Map.of("type", "string", "enum", List.of("content", "files_with_matches", "count"), "default", "files_with_matches"),
                                "context", Map.of("type", "integer", "description", "Context lines before and after match"),
                                "case_insensitive", Map.of("type", "boolean", "default", false),
                                "head_limit", Map.of("type", "integer", "default", DEFAULT_HEAD_LIMIT)
                        ),
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin_grep", List.of("filesystem", "search"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String pattern = (String) input.get("pattern");
        String basePath = (String) input.getOrDefault("path", null);
        String glob = (String) input.getOrDefault("glob", null);
        String outputMode = (String) input.getOrDefault("output_mode", "files_with_matches");
        Integer contextLines = input.containsKey("context") ? ((Number) input.get("context")).intValue() : null;
        boolean caseInsensitive = Boolean.TRUE.equals(input.get("case_insensitive"));
        int headLimit = input.containsKey("head_limit") ? ((Number) input.get("head_limit")).intValue() : DEFAULT_HEAD_LIMIT;

        Path searchRoot = basePath != null
                ? workspaceResolver.resolve(ctx, basePath)
                : workspaceResolver.getWorkspaceRoot(ctx);

        try {
            List<String> cmd = buildRipgrepCommand(pattern, searchRoot, glob, outputMode, contextLines, caseInsensitive);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(searchRoot.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < headLimit) {
                    lines.add(line);
                }
            }
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();

            boolean truncated = lines.size() >= headLimit;
            long durationMs = System.currentTimeMillis() - start;

            Map<String, Object> output = Map.of(
                    "matches", lines,
                    "matchCount", lines.size(),
                    "durationMs", durationMs,
                    "truncated", truncated
            );

            String summary = String.format("grep '%s': %d results%s",
                    pattern, lines.size(), truncated ? " (limited)" : "");

            return new ToolResult(true, output, summary,
                    List.of(), List.of(),
                    Map.of("pattern", pattern, "matchCount", lines.size()),
                    null, durationMs);

        } catch (Exception e) {
            log.info("ripgrep not found, using Java regex fallback: {}", e.getMessage());
            return javaFallbackGrep(pattern, searchRoot, glob, outputMode, caseInsensitive, headLimit, start);
        }
    }

    /**
     * ripgrep 미설치 시 Java regex 기반 fallback.
     */
    private ToolResult javaFallbackGrep(String pattern, Path searchRoot, String glob,
                                         String outputMode, boolean caseInsensitive,
                                         int headLimit, long start) {
        try {
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            Pattern regex = Pattern.compile(pattern, flags);
            PathMatcher fileMatcher = glob != null
                    ? FileSystems.getDefault().getPathMatcher("glob:" + glob)
                    : null;

            List<String> matches = new ArrayList<>();
            Set<String> matchedFiles = new HashSet<>();

            Files.walkFileTree(searchRoot, EnumSet.noneOf(FileVisitOption.class), 20, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= headLimit) return FileVisitResult.TERMINATE;

                    Path relative = searchRoot.relativize(file);
                    if (fileMatcher != null && !fileMatcher.matches(relative)) return FileVisitResult.CONTINUE;

                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size() && matches.size() < headLimit; i++) {
                            if (regex.matcher(lines.get(i)).find()) {
                                if ("files_with_matches".equals(outputMode)) {
                                    if (matchedFiles.add(relative.toString())) {
                                        matches.add(relative.toString());
                                    }
                                    break;
                                } else if ("count".equals(outputMode)) {
                                    matchedFiles.add(relative.toString());
                                } else {
                                    matches.add(relative + ":" + (i + 1) + ":" + lines.get(i));
                                }
                            }
                        }
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.equals(".git") || name.equals("node_modules") || name.equals("build")
                            || name.equals("target") || name.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            if ("count".equals(outputMode)) {
                matches.clear();
                matches.add(String.valueOf(matchedFiles.size()));
            }

            boolean truncated = matches.size() >= headLimit;
            long durationMs = System.currentTimeMillis() - start;

            Map<String, Object> output = Map.of(
                    "matches", matches,
                    "matchCount", matches.size(),
                    "durationMs", durationMs,
                    "truncated", truncated
            );

            String summary = String.format("grep '%s': %d results%s (java fallback)",
                    pattern, matches.size(), truncated ? " (limited)" : "");

            return new ToolResult(true, output, summary,
                    List.of(), List.of(),
                    Map.of("pattern", pattern, "matchCount", matches.size()),
                    null, durationMs);

        } catch (Exception e) {
            return ToolResult.error("Grep fallback failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private List<String> buildRipgrepCommand(String pattern, Path searchRoot, String glob,
                                              String outputMode, Integer contextLines, boolean caseInsensitive) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");

        if (caseInsensitive) cmd.add("-i");

        switch (outputMode) {
            case "files_with_matches" -> cmd.add("-l");
            case "count" -> cmd.add("-c");
            default -> cmd.add("-n"); // content mode: 줄 번호
        }

        if (contextLines != null && "content".equals(outputMode)) {
            cmd.add("-C");
            cmd.add(String.valueOf(contextLines));
        }

        if (glob != null && !glob.isBlank()) {
            cmd.add("--glob");
            cmd.add(glob);
        }

        cmd.add(pattern);
        cmd.add(searchRoot.toString());
        return cmd;
    }
}
