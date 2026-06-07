package com.platform.tool.nativetool;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspacePolicyEngine;
import com.platform.tool.workspace.WorkspacePolicy;
import com.platform.tool.workspace.WorkspaceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * CR-029 (PRD-170): 파일 내용을 제한된 범위로 읽기.
 * 바이너리 감지, workspace 경로 제한, 이미지/PDF는 메타만 반환.
 *
 * <p>CR-100: {@code as_base64=true} 면 바이너리 파일을 base64 로 반환한다.
 * 로컬 PC 의 PDF/DOCX 등을 CLI 가 읽어 {@code parse_document(content=base64)} 로
 * 서버 사이드카에 넘기는 "파싱 다리" 의 byte 획득 단계.
 */
public class FileReadTool implements EnhancedToolExecutor {

    /** CR-100: base64 반환 상한(원본 byte 기준). stdio/relay 페이로드 부담 고려해 보수적으로. */
    private static final long MAX_BASE64_SOURCE_BYTES = 10L * 1024 * 1024;

    private final WorkspaceResolver workspaceResolver;
    private final WorkspacePolicyEngine policyEngine;

    public FileReadTool(WorkspaceResolver workspaceResolver, WorkspacePolicyEngine policyEngine) {
        this.workspaceResolver = workspaceResolver;
        this.policyEngine = policyEngine;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_file_read",
                "Reads a file from the filesystem.\n\nUsage:\n- The file_path parameter MUST be an absolute path, not a relative path\n- By default, reads up to 2000 lines from the beginning\n- Use offset and limit to read specific ranges\n- Results are returned in cat -n format with line numbers\n- Binary files return metadata only\n- For binary documents (PDF/DOCX/XLSX/etc.) you want to parse, set as_base64=true to get the file as base64, then pass it to parse_document(content=...)\n- Cannot read directories. Use builtin_glob to list directory contents\n- Pass exact file paths returned by builtin_glob or builtin_grep to this tool",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "File path to read (absolute path)"),
                                "offset", Map.of("type", "integer", "description", "Line offset (0-based)", "default", 0),
                                "limit", Map.of("type", "integer", "description", "Maximum number of lines to read", "default", 2000),
                                "encoding", Map.of("type", "string", "description", "File encoding", "default", "UTF-8"),
                                "as_base64", Map.of("type", "boolean", "description", "Return the raw file bytes as base64 instead of text. Use for binary documents you intend to parse via parse_document.", "default", false)
                        ),
                        "required", List.of("file_path")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin_file_read", List.of("filesystem", "read"));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ValidationResult.fail("file_path is required.");
        }
        return policyEngine.validatePath(ctx, WorkspacePolicy.defaultPolicy(), filePath);
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String filePath = (String) input.get("file_path");
        int offset = input.containsKey("offset") ? ((Number) input.get("offset")).intValue() : 0;
        int limit = input.containsKey("limit") ? ((Number) input.get("limit")).intValue() : 2000;
        boolean asBase64 = Boolean.TRUE.equals(input.get("as_base64"));

        try {
            Path resolved = workspaceResolver.resolve(ctx, filePath);
            if (!Files.exists(resolved)) {
                return ToolResult.error("File not found: " + filePath)
                        .withDuration(System.currentTimeMillis() - start);
            }

            // CR-100: base64 모드 — 바이너리/텍스트 무관하게 raw 바이트를 base64 로 반환.
            // 로컬 PC 문서를 parse_document(content=base64) 로 서버 사이드카에 넘기는 다리.
            if (asBase64) {
                long size = Files.size(resolved);
                if (size > MAX_BASE64_SOURCE_BYTES) {
                    return ToolResult.error("File too large for base64: " + size + " bytes (max "
                            + MAX_BASE64_SOURCE_BYTES + "). Consider parse_document(file_path=...) on the server side.")
                            .withDuration(System.currentTimeMillis() - start);
                }
                byte[] raw = Files.readAllBytes(resolved);
                String b64 = Base64.getEncoder().encodeToString(raw);
                String ext = getExtension(resolved);
                Map<String, Object> output = Map.of(
                        "path", filePath,
                        "extension", ext,
                        "size", size,
                        "encoding", "base64",
                        "content", b64
                );
                return new ToolResult(true, output,
                        "base64 file: " + filePath + " (" + size + " bytes)",
                        List.of(new ToolArtifact("file_base64", filePath, null)),
                        List.of(), Map.of("file_path", filePath, "size", size, "encoding", "base64"),
                        null, System.currentTimeMillis() - start);
            }

            // 바이너리 감지 (첫 8KB)
            byte[] head = Files.readAllBytes(resolved);
            int checkSize = Math.min(head.length, 8192);
            for (int i = 0; i < checkSize; i++) {
                if (head[i] == 0) {
                    long size = Files.size(resolved);
                    String ext = getExtension(resolved);
                    Map<String, Object> meta = Map.of(
                            "path", filePath, "extension", ext,
                            "size", size, "binary", true,
                            "message", "Binary file - metadata only"
                    );
                    return new ToolResult(true, meta,
                            "Binary file: " + filePath + " (" + size + " bytes)",
                            List.of(new ToolArtifact("file_meta", filePath, meta)),
                            List.of(), Map.of(), null,
                            System.currentTimeMillis() - start);
                }
            }

            // 텍스트 읽기
            List<String> allLines = Files.readAllLines(resolved);
            int totalLines = allLines.size();
            int fromLine = Math.min(offset, totalLines);
            int toLine = Math.min(fromLine + limit, totalLines);
            List<String> lines = allLines.subList(fromLine, toLine);
            boolean truncated = toLine < totalLines;

            // 줄 번호 붙여서 내용 생성
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                content.append(fromLine + i + 1).append("\t").append(lines.get(i)).append("\n");
            }

            String ext = getExtension(resolved);
            long size = Files.size(resolved);
            Map<String, Object> output = Map.of(
                    "path", filePath,
                    "extension", ext,
                    "language", inferLanguage(ext),
                    "size", size,
                    "content", content.toString(),
                    "truncated", truncated,
                    "lineCount", totalLines
            );

            String summary = truncated
                    ? String.format("%s (%d/%d줄, offset=%d)", filePath, lines.size(), totalLines, fromLine)
                    : String.format("%s (%d줄)", filePath, totalLines);

            return new ToolResult(true, output, summary,
                    List.of(new ToolArtifact("file_content", filePath, null)),
                    List.of(), Map.of("file_path", filePath, "lines_read", lines.size()),
                    null, System.currentTimeMillis() - start);

        } catch (IOException e) {
            return ToolResult.error("File read failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private String inferLanguage(String ext) {
        return switch (ext) {
            case "java" -> "java";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx" -> "javascript";
            case "py" -> "python";
            case "sql" -> "sql";
            case "md" -> "markdown";
            case "yml", "yaml" -> "yaml";
            case "json" -> "json";
            case "xml" -> "xml";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "sh", "bash" -> "shell";
            case "go" -> "go";
            case "rs" -> "rust";
            case "kt", "kts" -> "kotlin";
            default -> "text";
        };
    }
}
