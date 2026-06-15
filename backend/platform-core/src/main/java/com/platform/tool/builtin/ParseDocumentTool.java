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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>CR-110: 기본 비활성화. {@code aimbase.parse-document.tool-enabled=true} 일 때만 빈 등록 →
 * API/CLI 도구 목록에서 제외된다. PDF/문서는 모델/CLI 가 비전으로 직접 읽고, 사이드카 파싱은
 * {@link MCPRagClient#parseDocument} 직접 호출 경로(첨부 자동 파싱 등, 도구 레지스트리 무관)로만 사용한다.</p>
 */
@Component
@ConditionalOnProperty(name = "aimbase.parse-document.tool-enabled", havingValue = "true", matchIfMissing = false)
public class ParseDocumentTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParseDocumentTool.class);

    private static final List<String> SUPPORTED_TYPES =
            List.of("pdf", "docx", "pptx", "xlsx", "csv", "html", "txt", "md");

    private static final int MAX_FILE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_TEXT_RESPONSE_CHARS = 32_000;
    /** 스키마 설명용 — 실제 상한은 PdfVisionResolver(aimbase.pdf.max-pages-per-read). */
    private static final int DEFAULT_MAX_PAGES_PER_READ = 20;

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
                                ),
                                "pages", Map.of(
                                        "type", "string",
                                        "description", "PDF only. Page range, 1-indexed (e.g. \"1-10\", \"3\", \"11-20\"). "
                                                + "If the document has too many pages to read at once, this tool returns "
                                                + "the page count and asks you to call again with this parameter for "
                                                + "specific ranges (max " + DEFAULT_MAX_PAGES_PER_READ + " pages per call)."
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
        String pages = stringOrNull(input.get("pages"));  // CR-095: PDF 페이지 분할 범위
        if (fileType != null) fileType = fileType.toLowerCase(Locale.ROOT);

        try {
            if (url != null) {
                // CR-107: url 로 들어온 PDF 도 content/file_path 갈래와 동일하게 비전 경로로 통일한다.
                // 기존엔 url 갈래에 PDF 분기가 없어 PDF 가 사이드카 텍스트 추출(pdfplumber)로 새어
                // 벡터 많은 페이지에서 페이지당 수~십 초 + MCP 180s timeout 으로 막혔다.
                // file_type 명시값 우선, 없으면 url 확장자로 추론. 확장자가 없으면(예: .../download)
                // BE 가 받은 바이트 매직넘버(%PDF)로 최종 판정한다.
                String urlType = fileType != null ? fileType : inferTypeFromUrl(url);
                if ("pdf".equals(urlType)) {
                    byte[] bytes = downloadBytes(url);
                    if (bytes.length > MAX_FILE_BYTES) {
                        return ToolResult.error("File too large: " + bytes.length + " bytes (max " + MAX_FILE_BYTES + ")")
                                .withDuration(System.currentTimeMillis() - start);
                    }
                    return buildVisionResult(bytes, Path.of(filenameFromUrl(url)), pages, start);
                }
                if (urlType.isEmpty()) {
                    // 확장자 미상 — 바이트를 받아 매직넘버로 PDF 인지 확인. PDF 면 비전, 아니면 사이드카로.
                    byte[] bytes = downloadBytes(url);
                    if (bytes.length > MAX_FILE_BYTES) {
                        return ToolResult.error("File too large: " + bytes.length + " bytes (max " + MAX_FILE_BYTES + ")")
                                .withDuration(System.currentTimeMillis() - start);
                    }
                    if (isPdfMagic(bytes)) {
                        return buildVisionResult(bytes, Path.of(filenameFromUrl(url)), pages, start);
                    }
                    // 비-PDF — base64 로 사이드카 텍스트 추출 (이미 받은 바이트 재사용, url 재다운로드 없음)
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    Map<String, Object> result = ragClient.parseDocument(base64, "");
                    return buildResult(result, "url:" + url, start);
                }
                // 비-PDF 명시 타입(DOCX/XLSX 등) — 기존대로 사이드카가 url 직접 다운로드+추출
                Map<String, Object> sidecarInput = new LinkedHashMap<>();
                sidecarInput.put("url", url);
                sidecarInput.put("file_type", urlType);
                Map<String, Object> result = ragClient.callToolRaw("parse_document", sidecarInput);
                return buildResult(result, "url:" + url, start);
            } else if (content != null) {
                // CR-100: 로컬 PC base64 — 사이드카가 같은 파일시스템이 아니므로 항상 base64 전달.
                // PDF 면 비전 경로(BE 가 디코드해 document/image 블록 주입), 그 외는 사이드카 텍스트 추출.
                if ("pdf".equals(fileType)) {
                    byte[] bytes = Base64.getDecoder().decode(content);
                    return buildVisionResult(bytes, Path.of("local.pdf"), pages, start);
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
                    return buildVisionResult(bytes, p, pages, start);
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
    private ToolResult buildVisionResult(byte[] bytes, Path p, String pages, long start) {
        // CR-095: allowSplitGuard=true — 도구 경로는 모델이 pages 로 재호출 가능하므로
        // 페이지 수 임계(기본 10) 초과 시 NEEDS_SPLIT 으로 분할을 모델에게 위임한다.
        com.platform.attachment.PdfVisionResolver.PdfVisionResult vision =
                pdfVisionResolver.resolve(bytes, true, true, pages, true);

        String filename = p.getFileName().toString();
        java.util.List<com.platform.tool.ToolMessageBlock> injected = new java.util.ArrayList<>();
        String summary;
        Map<String, Object> output = new LinkedHashMap<>();

        switch (vision.mode()) {
            case NEEDS_SPLIT -> {
                // 통째 처리하면 turn timeout — 모델에게 pages 로 나눠 호출하라고 안내(openclaude 가드).
                Map<String, Object> splitOut = new LinkedHashMap<>();
                splitOut.put("status", "too_many_pages");
                splitOut.put("total_pages", vision.totalPages());
                splitOut.put("max_pages_per_call", pdfVisionResolver.maxPagesPerRead());
                splitOut.put("instruction", vision.note());
                splitOut.put("source", "file:" + p);
                return new ToolResult(true, splitOut,
                        "PDF too large (" + vision.totalPages() + " pages) — call again with pages parameter",
                        List.of(), List.of(),
                        Map.of("source", "file:" + p, "status", "too_many_pages"),
                        null, System.currentTimeMillis() - start, List.of());
            }
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
                if (vision.note() != null) output.put("note_pages", vision.note());
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
        // CR-101: 문구 채널 중립화 — API 경로는 별도 user 메시지로, MCP(CLI) 경로는 같은
        // tool result 의 content 블록으로 실리므로 위치를 단정하지 않는다.
        output.put("note", "The PDF content has been provided to you directly, either as "
                + "attachment blocks in this tool result or as a separate message. "
                + "Read it with vision; do not call parse_document again for this file.");

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

    /**
     * CR-107: url 의 경로 끝 확장자로 타입 추론. 쿼리스트링/프래그먼트는 무시한다.
     * 확장자가 없거나(예: {@code .../download}) 미지원이면 "" 반환 → 호출부가 매직넘버로 판정.
     */
    private String inferTypeFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) return "";
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return inferTypeFromPath(Path.of(name.isBlank() ? "x" : name));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** CR-107: url 경로 끝 파일명 추출 (비전 결과 메타의 filename 표기용). 없으면 "download.pdf". */
    private String filenameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                String name = slash >= 0 ? path.substring(slash + 1) : path;
                if (!name.isBlank()) return name;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "download.pdf";
    }

    /** CR-107: PDF 매직넘버 {@code %PDF} 판정 (url 확장자가 없을 때 비전/텍스트 분기 결정). */
    private static boolean isPdfMagic(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46; // %PDF
    }

    /**
     * CR-107: BE 가 url 에서 바이트를 직접 받는다(PDF 비전 경로용). 다운로드는 실측 ~0.03s.
     * MAX_FILE_BYTES 초과분은 호출부가 별도 체크 — 여기선 받기만 한다.
     */
    private byte[] downloadBytes(String url) throws java.io.IOException, InterruptedException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(60))
                .GET()
                .build();
        java.net.http.HttpResponse<byte[]> resp =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new java.io.IOException("download failed: HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private static String stringOrNull(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
