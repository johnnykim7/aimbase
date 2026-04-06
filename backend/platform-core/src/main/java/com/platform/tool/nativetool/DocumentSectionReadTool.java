package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CR-029 (PRD-176): 문서의 특정 섹션, anchor, line range를 선택적으로 읽기.
 * Markdown heading(#~######), line range 모드 지원.
 */
@Component
public class DocumentSectionReadTool implements EnhancedToolExecutor {

    private final WorkspaceResolver workspaceResolver;

    public DocumentSectionReadTool(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_document_section_read",
                "Reads specific sections of a document by heading. Call without section param to list all sections first.\n\n- Call without section param to get a list of all sections\n- Specify section param to read only that heading's content\n- Use line_range to read specific line ranges (e.g., '10-30')\n- Best suited for Markdown documents with # headings",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Document file path (absolute)"),
                                "section", Map.of("type", "string", "description", "Heading text to find (partial match)"),
                                "section_level", Map.of("type", "integer", "description", "Heading level (1-6)"),
                                "line_range", Map.of("type", "string", "description", "Line range (e.g., 10-30)")
                        ),
                        "required", List.of("file_path")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin_document_section_read", List.of("document", "read", "section"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String filePath = (String) input.get("file_path");
        String sectionQuery = (String) input.getOrDefault("section", null);
        Integer sectionLevel = input.containsKey("section_level") ? ((Number) input.get("section_level")).intValue() : null;
        String lineRange = (String) input.getOrDefault("line_range", null);

        Path resolved = workspaceResolver.resolve(ctx, filePath);
        if (!Files.exists(resolved)) {
            return ToolResult.error("File not found: " + filePath)
                    .withDuration(System.currentTimeMillis() - start);
        }

        try {
            List<String> allLines = Files.readAllLines(resolved);

            // line_range 모드
            if (lineRange != null) {
                return readLineRange(filePath, allLines, lineRange, start);
            }

            // section 모드
            if (sectionQuery != null) {
                return readSection(filePath, allLines, sectionQuery, sectionLevel, start);
            }

            // 섹션 목록 반환
            return listSections(filePath, allLines, start);

        } catch (IOException e) {
            return ToolResult.error("File read failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private ToolResult readLineRange(String filePath, List<String> allLines, String range, long start) {
        String[] parts = range.split("-");
        int from = Integer.parseInt(parts[0].trim()) - 1;
        int to = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : from + 1;
        from = Math.max(0, from);
        to = Math.min(allLines.size(), to);

        StringBuilder content = new StringBuilder();
        for (int i = from; i < to; i++) {
            content.append(i + 1).append("\t").append(allLines.get(i)).append("\n");
        }

        Map<String, Object> output = Map.of(
                "path", filePath,
                "content", content.toString(),
                "lineStart", from + 1,
                "lineEnd", to,
                "totalLines", allLines.size()
        );

        return new ToolResult(true, output,
                String.format("%s (줄 %d-%d)", filePath, from + 1, to),
                List.of(), List.of(), Map.of(), null, System.currentTimeMillis() - start);
    }

    private ToolResult readSection(String filePath, List<String> allLines, String query,
                                    Integer levelFilter, long start) {
        int sectionStart = -1;
        int sectionLevel = 0;
        String sectionTitle = null;

        // 섹션 시작 찾기
        for (int i = 0; i < allLines.size(); i++) {
            String line = allLines.get(i);
            int level = getHeadingLevel(line);
            if (level > 0) {
                String title = line.replaceFirst("^#+\\s*", "").trim();
                if (title.toLowerCase().contains(query.toLowerCase())) {
                    if (levelFilter == null || level == levelFilter) {
                        sectionStart = i;
                        sectionLevel = level;
                        sectionTitle = title;
                        break;
                    }
                }
            }
        }

        if (sectionStart < 0) {
            return ToolResult.error("Section not found: " + query)
                    .withDuration(System.currentTimeMillis() - start);
        }

        // 섹션 끝 찾기 (같거나 높은 레벨의 다음 heading)
        int sectionEnd = allLines.size();
        for (int i = sectionStart + 1; i < allLines.size(); i++) {
            int level = getHeadingLevel(allLines.get(i));
            if (level > 0 && level <= sectionLevel) {
                sectionEnd = i;
                break;
            }
        }

        StringBuilder content = new StringBuilder();
        for (int i = sectionStart; i < sectionEnd; i++) {
            content.append(i + 1).append("\t").append(allLines.get(i)).append("\n");
        }

        Map<String, Object> output = Map.of(
                "path", filePath,
                "section_title", sectionTitle,
                "content", content.toString(),
                "lineStart", sectionStart + 1,
                "lineEnd", sectionEnd
        );

        return new ToolResult(true, output,
                String.format("%s > %s (줄 %d-%d)", filePath, sectionTitle, sectionStart + 1, sectionEnd),
                List.of(), List.of(), Map.of(), null, System.currentTimeMillis() - start);
    }

    private ToolResult listSections(String filePath, List<String> allLines, long start) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (int i = 0; i < allLines.size(); i++) {
            int level = getHeadingLevel(allLines.get(i));
            if (level > 0) {
                String title = allLines.get(i).replaceFirst("^#+\\s*", "").trim();
                sections.add(Map.of("level", level, "title", title, "line", i + 1));
            }
        }

        Map<String, Object> output = Map.of(
                "path", filePath,
                "totalSections", sections.size(),
                "sections", sections
        );

        return new ToolResult(true, output,
                String.format("%s: %d sections", filePath, sections.size()),
                List.of(), List.of(), Map.of(), null, System.currentTimeMillis() - start);
    }

    private int getHeadingLevel(String line) {
        if (!line.startsWith("#")) return 0;
        int level = 0;
        for (char c : line.toCharArray()) {
            if (c == '#') level++;
            else break;
        }
        return (level <= 6 && line.length() > level && line.charAt(level) == ' ') ? level : 0;
    }
}
