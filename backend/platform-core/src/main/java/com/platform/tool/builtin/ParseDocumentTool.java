package com.platform.tool.builtin;

import com.platform.config.WorkspaceProperties;
import com.platform.rag.MCPRagClient;
import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.PermissionLevel;
import com.platform.tool.RetryPolicy;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolResult;
import com.platform.tool.ToolScope;
import com.platform.tool.ValidationResult;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CR-094: 문서 파싱 도구 — AGENT 자율 호출 입구.
 *
 * 사이드카 {@code parse_document} MCP 도구를 BE Tool 인터페이스로 노출한다.
 * Native Read tool 이 binary 로 거부하는 DOCX/XLSX/PPTX 등을 LLM 이 직접
 * 쥐고 처리할 수 있게 한다 (PDF 는 native 비전이 더 효율적이지만 일관성 위해 함께 지원).
 *
 * 백엔드: {@link MCPRagClient#parseDocument(String, String)}
 *        스캔 PDF 처리는 사이드카 측에서 OCR fallback 처리.
 */
@Component
public class ParseDocumentTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParseDocumentTool.class);

    private static final List<String> SUPPORTED_TYPES =
            List.of("pdf", "docx", "pptx", "xlsx", "csv", "html", "txt", "md");

    private static final int MAX_FILE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_TEXT_RESPONSE_CHARS = 32_000;

    private final MCPRagClient ragClient;
    private final WorkspaceProperties workspaceProperties;

    public ParseDocumentTool(MCPRagClient ragClient, WorkspaceProperties workspaceProperties) {
        this.ragClient = ragClient;
        this.workspaceProperties = workspaceProperties;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "parse_document",
                "Parse a document (PDF, DOCX, PPTX, XLSX, CSV, HTML, TXT, Markdown) " +
                        "into plain text. Source can be a URL (auto-downloaded) or a file path " +
                        "in the workspace. For scanned PDFs without embedded text, OCR fallback " +
                        "is attempted automatically. " +
                        "Use this when the user attaches or references a non-PDF document and you " +
                        "need to read its content. (Native Read tool handles PDFs directly via " +
                        "vision; this tool is mainly for DOCX/XLSX/PPTX/etc.)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of(
                                        "type", "string",
                                        "description", "HTTP(S) URL of the document. Either 'url' or 'file_path' is required."
                                ),
                                "file_path", Map.of(
                                        "type", "string",
                                        "description", "Absolute path inside the workspace whitelist. Either 'url' or 'file_path' is required."
                                ),
                                "file_type", Map.of(
                                        "type", "string",
                                        "enum", SUPPORTED_TYPES,
                                        "description", "File format hint. Auto-detected from URL/file extension if omitted."
                                )
                        )
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "parse_document", "1.0", ToolScope.BUILTIN,
                PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE,
                List.of("document", "parse", "extract"),
                List.of("read", "extract")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String url = stringOrNull(input.get("url"));
        String filePath = stringOrNull(input.get("file_path"));

        if (url == null && filePath == null) {
            return ValidationResult.fail("Either 'url' or 'file_path' is required.");
        }
        if (url != null && filePath != null) {
            return ValidationResult.fail("Provide either 'url' or 'file_path', not both.");
        }
        if (url != null) {
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    return ValidationResult.fail("url must use http or https scheme.");
                }
            } catch (IllegalArgumentException e) {
                return ValidationResult.fail("Invalid url: " + e.getMessage());
            }
        }
        if (filePath != null) {
            Path p = Path.of(filePath).toAbsolutePath().normalize();
            if (!workspaceProperties.isInsideWhitelist(p)) {
                return ValidationResult.fail("file_path is outside workspace whitelist.");
            }
        }
        Object ft = input.get("file_type");
        if (ft instanceof String s && !s.isBlank() && !SUPPORTED_TYPES.contains(s.toLowerCase(Locale.ROOT))) {
            return ValidationResult.fail("Unsupported file_type. Allowed: " + SUPPORTED_TYPES);
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String url = stringOrNull(input.get("url"));
        String filePath = stringOrNull(input.get("file_path"));
        String fileType = stringOrNull(input.get("file_type"));
        if (fileType != null) fileType = fileType.toLowerCase(Locale.ROOT);

        try {
            if (url != null) {
                Map<String, Object> sidecarInput = new LinkedHashMap<>();
                sidecarInput.put("url", url);
                sidecarInput.put("file_type", fileType != null ? fileType : "");
                Map<String, Object> result = ragClient.callToolRaw("parse_document", sidecarInput);
                return buildResult(result, "url:" + url, start);
            } else {
                Path p = Path.of(filePath).toAbsolutePath().normalize();
                byte[] bytes = Files.readAllBytes(p);
                if (bytes.length > MAX_FILE_BYTES) {
                    return ToolResult.error("File too large: " + bytes.length + " bytes (max " + MAX_FILE_BYTES + ")")
                            .withDuration(System.currentTimeMillis() - start);
                }
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String inferredType = fileType != null ? fileType : inferTypeFromPath(p);
                Map<String, Object> result = ragClient.parseDocument(base64, inferredType);
                return buildResult(result, "file:" + p, start);
            }
        } catch (Exception e) {
            log.error("parse_document failed: {}", e.getMessage());
            return ToolResult.error("parse_document failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private ToolResult buildResult(Map<String, Object> sidecarResult, String source, long start) {
        String text = sidecarResult.get("text") instanceof String s ? s : "";
        Integer pages = null;
        String detectedType = null;
        if (sidecarResult.get("metadata") instanceof Map<?, ?> meta) {
            Object p = meta.get("pages");
            if (p instanceof Number n) pages = n.intValue();
            Object ft = meta.get("file_type");
            if (ft instanceof String s) detectedType = s;
        }

        boolean truncated = false;
        if (text.length() > MAX_TEXT_RESPONSE_CHARS) {
            text = text.substring(0, MAX_TEXT_RESPONSE_CHARS) + "\n[... truncated, full length: " + text.length() + " chars ...]";
            truncated = true;
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", text);
        output.put("character_count", text.length());
        output.put("source", source);
        if (pages != null) output.put("pages", pages);
        if (detectedType != null) output.put("file_type", detectedType);
        if (truncated) output.put("truncated", true);

        String summary = "Parsed " + (detectedType != null ? detectedType : "document") +
                " — " + text.length() + " chars" + (pages != null ? ", " + pages + " pages" : "");

        return new ToolResult(true, output, summary,
                List.of(), List.of(),
                Map.of("source", source, "characterCount", text.length()),
                null, System.currentTimeMillis() - start);
    }

    private String inferTypeFromPath(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1);
        if ("markdown".equals(ext)) return "md";
        return SUPPORTED_TYPES.contains(ext) ? ext : "";
    }

    private static String stringOrNull(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
