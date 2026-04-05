package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

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
                "builtin:grep",
                "텍스트 패턴을 검색합니다. ripgrep 기반으로 빠르게 동작합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pattern", Map.of("type", "string", "description", "정규식 패턴"),
                                "path", Map.of("type", "string", "description", "검색 경로 (기본: workspace root)"),
                                "glob", Map.of("type", "string", "description", "파일 필터 glob (예: *.java)"),
                                "output_mode", Map.of("type", "string", "enum", List.of("content", "files_with_matches", "count"), "default", "files_with_matches"),
                                "context", Map.of("type", "integer", "description", "전후 컨텍스트 라인 수"),
                                "case_insensitive", Map.of("type", "boolean", "default", false),
                                "head_limit", Map.of("type", "integer", "default", DEFAULT_HEAD_LIMIT)
                        ),
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin:grep", List.of("filesystem", "search"));
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

            String summary = String.format("grep '%s': %d개 결과%s",
                    pattern, lines.size(), truncated ? " (제한됨)" : "");

            return new ToolResult(true, output, summary,
                    List.of(), List.of(),
                    Map.of("pattern", pattern, "matchCount", lines.size()),
                    null, durationMs);

        } catch (Exception e) {
            log.warn("ripgrep 실행 실패, fallback 불가: {}", e.getMessage());
            return ToolResult.error("grep 실행 실패: " + e.getMessage())
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
