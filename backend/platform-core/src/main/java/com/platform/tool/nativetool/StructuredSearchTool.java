package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CR-029 (PRD-175): class/function/config_key/entity_field/json_path 구조 검색.
 * search_type별 전략 패턴.
 */
@Component
public class StructuredSearchTool implements EnhancedToolExecutor {

    private static final int DEFAULT_LIMIT = 50;

    private final WorkspaceResolver workspaceResolver;

    public StructuredSearchTool(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_structured_search",
                "Searches for code structure elements like classes, functions, config keys, and entity fields.\n\n- search_type: class (class definitions), function (function definitions), config_key (config keys), entity_field (entity fields)\n- Use builtin_grep for general text search, use this tool for structural code element search",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Search query"),
                                "search_type", Map.of("type", "string",
                                        "enum", List.of("class", "function", "config_key", "entity_field"),
                                        "description", "Search type"),
                                "path", Map.of("type", "string", "description", "Search path"),
                                "glob", Map.of("type", "string", "description", "File filter (e.g., *.java)"),
                                "limit", Map.of("type", "integer", "default", DEFAULT_LIMIT)
                        ),
                        "required", List.of("query", "search_type")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin_structured_search", List.of("code", "search", "structure"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String query = (String) input.get("query");
        String searchType = (String) input.get("search_type");
        String basePath = (String) input.getOrDefault("path", null);
        String globPattern = (String) input.getOrDefault("glob", null);
        int limit = input.containsKey("limit") ? ((Number) input.get("limit")).intValue() : DEFAULT_LIMIT;

        Path searchRoot = basePath != null
                ? workspaceResolver.resolve(ctx, basePath)
                : workspaceResolver.getWorkspaceRoot(ctx);

        Pattern regex = buildPattern(query, searchType);
        String defaultGlob = inferGlob(searchType, globPattern);
        PathMatcher fileMatcher = defaultGlob != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + defaultGlob)
                : null;

        List<Map<String, Object>> results = new ArrayList<>();

        try {
            Files.walkFileTree(searchRoot, EnumSet.noneOf(FileVisitOption.class), 20, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= limit) return FileVisitResult.TERMINATE;

                    Path relative = searchRoot.relativize(file);
                    if (fileMatcher != null && !fileMatcher.matches(relative)) {
                        return FileVisitResult.CONTINUE;
                    }

                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            if (results.size() >= limit) break;
                            Matcher m = regex.matcher(lines.get(i));
                            if (m.find()) {
                                int contextStart = Math.max(0, i - 1);
                                int contextEnd = Math.min(lines.size(), i + 2);
                                String context = String.join("\n",
                                        lines.subList(contextStart, contextEnd));

                                results.add(Map.of(
                                        "file", relative.toString(),
                                        "line", i + 1,
                                        "name", m.group().trim(),
                                        "type", searchType,
                                        "context", context
                                ));
                            }
                        }
                    } catch (IOException ignored) {
                        // 바이너리 등 읽기 불가 파일 skip
                    }
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
        } catch (IOException e) {
            return ToolResult.error("Structured search failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }

        long durationMs = System.currentTimeMillis() - start;
        Map<String, Object> output = Map.of(
                "results", results,
                "resultCount", results.size(),
                "durationMs", durationMs
        );

        String summary = String.format("%s '%s': %d results", searchType, query, results.size());
        return new ToolResult(true, output, summary,
                List.of(), List.of(),
                Map.of("query", query, "searchType", searchType, "resultCount", results.size()),
                null, durationMs);
    }

    private Pattern buildPattern(String query, String searchType) {
        String escaped = Pattern.quote(query);
        return switch (searchType) {
            case "class" -> Pattern.compile(
                    "(class|interface|enum|record)\\s+" + escaped + "\\b");
            case "function" -> Pattern.compile(
                    "(public|private|protected|static|default|def|function|fun|func)\\s+[\\w<>\\[\\]]*\\s*" + escaped + "\\s*\\(");
            case "config_key" -> Pattern.compile(
                    "\\b" + escaped + "\\s*[=:]");
            case "entity_field" -> Pattern.compile(
                    "@Column.*|\\b(private|protected)\\s+\\w+\\s+" + escaped + "\\b");
            default -> Pattern.compile(escaped);
        };
    }

    private String inferGlob(String searchType, String explicitGlob) {
        if (explicitGlob != null) return explicitGlob;
        return switch (searchType) {
            case "class", "function", "entity_field" -> "**/*.{java,kt,ts,tsx,js,jsx,py,go,rs}";
            case "config_key" -> "**/*.{yml,yaml,properties,json,toml,env}";
            default -> null;
        };
    }
}
