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
    private final com.platform.attachment.PdfVisionResolver pdfVisionResolver;

    public ParseDocumentTool(MCPRagClient ragClient, WorkspaceProperties workspaceProperties,
                             com.platform.attachment.PdfVisionResolver pdfVisionResolver) {
        this.ragClient = ragClient;
        this.workspaceProperties = workspaceProperties;
        this.pdfVisionResolver = pdfVisionResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "parse_document",
                "Parse a document (PDF, DOCX, PPTX, XLSX, CSV, HTML, TXT, Markdown) " +
                        "into plain text. Source is one of: a URL (auto-downloaded), a file path " +
                        "in the workspace, or base64-encoded file content. For scanned PDFs without " +
                        "embedded text, OCR fallback is attempted automatically. " +
                        "Use 'content' for files on the user's local machine: first read them with " +
                        "builtin_file_read(as_base64=true), then pass the base64 here. " +
                        "Use this when the user attaches or references a non-PDF document and you " +
                        "need to read its content. (Native Read tool handles PDFs directly via " +
                        "vision; this tool is mainly for DOCX/XLSX/PPTX/etc.)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of(
                                        "type", "string",
                                        "description", "HTTP(S) URL of the document. Provide exactly one of 'url', 'file_path', 'content'."
                                ),
                                "file_path", Map.of(
                                        "type", "string",
                                        "description", "Absolute path inside the workspace whitelist (read by the server-side sidecar). Provide exactly one of 'url', 'file_path', 'content'."
                                ),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "Base64-encoded file bytes (for files on the user's local PC; obtain via builtin_file_read(as_base64=true)). Provide exactly one of 'url', 'file_path', 'content'."
                                ),
                                "file_type", Map.of(
                                        "type", "string",
                                        "enum", SUPPORTED_TYPES,
                                        "description", "File format hint. Auto-detected from URL/file extension if omitted; recommended when using 'content'."
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
        String content = stringOrNull(input.get("content"));

        int provided = (url != null ? 1 : 0) + (filePath != null ? 1 : 0) + (content != null ? 1 : 0);
        if (provided == 0) {
            return ValidationResult.fail("One of 'url', 'file_path', or 'content' is required.");
        }
        if (provided > 1) {
            return ValidationResult.fail("Provide exactly one of 'url', 'file_path', 'content'.");
        }
        if (content != null) {
            try {
                int decodedLen = Base64.getDecoder().decode(content).length;
                if (decodedLen > MAX_FILE_BYTES) {
                    return ValidationResult.fail("Decoded content too large: " + decodedLen
                            + " bytes (max " + MAX_FILE_BYTES + ").");
                }
            } catch (IllegalArgumentException e) {
                return ValidationResult.fail("content is not valid base64.");
            }
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
        String content = stringOrNull(input.get("content"));
        String fileType = stringOrNull(input.get("file_type"));
        if (fileType != null) fileType = fileType.toLowerCase(Locale.ROOT);

        try {
            if (url != null) {
                Map<String, Object> sidecarInput = new LinkedHashMap<>();
                sidecarInput.put("url", url);
                sidecarInput.put("file_type", fileType != null ? fileType : "");
                Map<String, Object> result = ragClient.callToolRaw("parse_document", sidecarInput);
                return buildResult(result, "url:" + url, start);
            } else if (content != null) {
                // CR-100: 로컬 PC base64 — 사이드카가 같은 파일시스템이 아니므로 항상 base64 전달.
                // PDF 면 비전 경로(BE 가 디코드해 document/image 블록 주입), 그 외는 사이드카 텍스트 추출.
                if ("pdf".equals(fileType)) {
                    byte[] bytes = Base64.getDecoder().decode(content);
                    return buildVisionResult(bytes, Path.of("local.pdf"), start);
                }
                Map<String, Object> result = ragClient.parseDocument(content, fileType != null ? fileType : "");
                return buildResult(result, "content:base64", start);
            } else {
                Path p = Path.of(filePath).toAbsolutePath().normalize();
                String inferredType = fileType != null ? fileType : inferTypeFromPath(p);
                // CR-095: PDF 는 텍스트 추출 대신 LLM 비전 파싱 — 사이드카가 읽는 게 아니라
                // LLM 이 직접 비전으로 본다. BE 가 읽어 document/image 블록을 newMessages 로 주입.
                if ("pdf".equals(inferredType)) {
                    byte[] bytes = Files.readAllBytes(p);
                    if (bytes.length > MAX_FILE_BYTES) {
                        return ToolResult.error("File too large: " + bytes.length + " bytes (max " + MAX_FILE_BYTES + ")")
                                .withDuration(System.currentTimeMillis() - start);
                    }
                    return buildVisionResult(bytes, p, start);
                }
                // 비-PDF(DOCX/XLSX/PPTX/CSV/HTML/TXT): 문서를 읽어 텍스트 추출 = 사이드카의 일.
                // LLM 이 준 file_path 를 BE 가 읽지 않고 그대로 사이드카에 패스(base64 왕복 제거).
                // 사이드카(같은 파일시스템)가 직접 읽는다. 화이트리스트는 BE(아래 validateInput)+사이드카 이중 방어.
                Map<String, Object> result = ragClient.parseDocumentByPath(p.toString(), inferredType);
                return buildResult(result, "file:" + p, start);
            }
        } catch (Exception e) {
            log.error("parse_document failed: {}", e.getMessage());
            return ToolResult.error("parse_document failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    /**
     * CR-095: PDF 비전 파싱 — tool_result 에는 메타 텍스트, 실제 PDF/이미지는 newMessages 로 주입.
     * openclaude FileReadTool 의 (tool_result 메타 + newMessages document/image) 패턴 1:1.
     *
     * AGENT 자율 호출은 호출 모델 capability 를 도구가 알 수 없으므로 PDF/이미지 지원을 가정한다
     * (≤3MB → document, >3MB → 페이지 이미지화). 미지원 모델이면 어댑터가 처리하거나
     * PdfTextExtractor 폴백(=resolve 의 TEXT_FALLBACK)으로 떨어진다.
     */
    private ToolResult buildVisionResult(byte[] bytes, Path p, long start) {
        com.platform.attachment.PdfVisionResolver.PdfVisionResult vision =
                pdfVisionResolver.resolve(bytes, true, true);

        String filename = p.getFileName().toString();
        java.util.List<com.platform.tool.ToolMessageBlock> injected = new java.util.ArrayList<>();
        String summary;
        Map<String, Object> output = new LinkedHashMap<>();

        switch (vision.mode()) {
            case INLINE_PDF -> {
                injected.add(com.platform.tool.ToolMessageBlock.document(
                        "application/pdf", vision.pdfBase64(), filename));
                summary = "PDF attached for vision parsing: " + filename + " (inline document)";
                output.put("mode", "inline_pdf");
                output.put("source", "file:" + p);
            }
            case PAGE_IMAGES -> {
                for (com.platform.attachment.PdfVisionResolver.PageImage img : vision.images()) {
                    injected.add(com.platform.tool.ToolMessageBlock.image(img.mediaType(), img.base64()));
                }
                summary = "PDF rendered to " + vision.images().size() + " page image(s) for vision parsing: " + filename
                        + (vision.note() != null ? " (" + vision.note() + ")" : "");
                output.put("mode", "page_images");
                output.put("page_count", vision.images().size());
                output.put("source", "file:" + p);
            }
            default -> {
                // TEXT_FALLBACK — 비전 불가. 사이드카 텍스트 추출로 폴백.
                String base64 = Base64.getEncoder().encodeToString(bytes);
                Map<String, Object> result = ragClient.parseDocument(base64, "pdf");
                return buildResult(result, "file:" + p, start);
            }
        }

        // tool_result 본문에는 안내만 — 실제 콘텐츠는 newMessages 로 LLM 이 직접 본다.
        output.put("note", "The PDF content has been provided to you as a separate message above. "
                + "Read it directly; do not call parse_document again for this file.");

        return new ToolResult(true, output, summary,
                List.of(), List.of(),
                Map.of("source", "file:" + p, "mode", output.get("mode")),
                null, System.currentTimeMillis() - start, injected);
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
