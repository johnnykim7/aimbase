package com.platform.tool.builtin;

import com.platform.config.WorkspaceProperties;
import com.platform.rag.MCPRagClient;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import com.platform.tool.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-094 ParseDocumentTool — 사이드카 parse_document 8종 포맷 BE 래퍼.
 */
@ExtendWith(MockitoExtension.class)
class ParseDocumentToolTest {

    @Mock
    private MCPRagClient ragClient;

    private WorkspaceProperties workspaceProperties;
    private ParseDocumentTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        workspaceProperties = new WorkspaceProperties();
        // CR-095: PDF 비전 게이트. 비-PDF(txt 등) 경로 테스트는 resolver 미호출이므로 실제 인스턴스로 충분.
        com.platform.attachment.PdfVisionResolver pdfVisionResolver =
                new com.platform.attachment.PdfVisionResolver(ragClient, 3145728L, 20971520L, 20, 100, 10);
        tool = new ParseDocumentTool(ragClient, workspaceProperties, pdfVisionResolver);
        ctx = ToolContext.minimal("test-tenant", "test-session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void definition_hasNameAndSchemaAndSupportedTypes() {
        var def = tool.getDefinition();
        assertThat(def.name()).isEqualTo("parse_document");
        assertThat(def.description()).contains("DOCX", "PPTX", "XLSX");
        Map<String, Object> properties = (Map<String, Object>) def.inputSchema().get("properties");
        assertThat(properties).containsKeys("url", "file_path", "content", "file_type");
        Map<String, Object> fileType = (Map<String, Object>) properties.get("file_type");
        List<String> allowed = (List<String>) fileType.get("enum");
        assertThat(allowed).containsExactlyInAnyOrder(
                "pdf", "docx", "pptx", "xlsx", "csv", "html", "txt", "md");
    }

    @Test
    void validate_neitherUrlNorFilePath_fails() {
        ValidationResult r = tool.validateInput(Map.of(), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_bothUrlAndFilePath_fails() {
        ValidationResult r = tool.validateInput(
                Map.of("url", "https://example.com/a.docx", "file_path", "/tmp/x.docx"), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_invalidUrlScheme_fails() {
        ValidationResult r = tool.validateInput(Map.of("url", "ftp://example.com/a.docx"), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_unsupportedFileType_fails() {
        ValidationResult r = tool.validateInput(
                Map.of("url", "https://example.com/a.docx", "file_type", "rtf"), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_filePathOutsideWhitelist_fails(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Path outside = tmp.resolve("outside.docx");
        workspaceProperties.setWhitelistRoots(List.of(tmp.resolve("allowed").toString()));
        ValidationResult r = tool.validateInput(Map.of("file_path", outside.toString()), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_url_ok() {
        ValidationResult r = tool.validateInput(Map.of("url", "https://example.com/a.docx"), ctx);
        assertThat(r.valid()).isTrue();
    }

    @Test
    void execute_urlDelegatesToSidecarWithUrlInput() {
        when(ragClient.callToolRaw(eq("parse_document"), anyMap()))
                .thenReturn(Map.of(
                        "text", "Hello DOCX content",
                        "metadata", Map.of("file_type", "docx", "pages", 3)
                ));

        ToolResult r = tool.execute(
                Map.of("url", "https://example.com/foo.docx", "file_type", "docx"), ctx);

        assertThat(r.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("text")).isEqualTo("Hello DOCX content");
        assertThat(out.get("character_count")).isEqualTo("Hello DOCX content".length());
        assertThat(out.get("source")).isEqualTo("url:https://example.com/foo.docx");
        assertThat(out.get("file_type")).isEqualTo("docx");
        assertThat(out.get("pages")).isEqualTo(3);
        verify(ragClient, never()).parseDocument(anyString(), anyString());
    }

    @Test
    void execute_filePathReadsBytesAndCallsParseDocument(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path doc = tmp.resolve("test.txt");
        Files.writeString(doc, "hello world");
        workspaceProperties.setWhitelistRoots(List.of(tmp.toString()));

        // 선행 작업(file_path 입력): BE 가 byte 를 읽지 않고 path 를 사이드카에 패스 → parseDocumentByPath.
        when(ragClient.parseDocumentByPath(anyString(), eq("txt")))
                .thenReturn(Map.of(
                        "text", "hello world",
                        "metadata", Map.of("file_type", "txt")
                ));

        ToolResult r = tool.execute(Map.of("file_path", doc.toString()), ctx);

        assertThat(r.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("text")).isEqualTo("hello world");
        assertThat(((String) out.get("source"))).startsWith("file:");
        verify(ragClient).parseDocumentByPath(anyString(), eq("txt"));
    }

    @Test
    void execute_longTextGetsTruncated() {
        String longText = "x".repeat(40_000);
        when(ragClient.callToolRaw(eq("parse_document"), anyMap()))
                .thenReturn(Map.of("text", longText, "metadata", Map.of("file_type", "html")));

        ToolResult r = tool.execute(Map.of("url", "https://example.com/big.html"), ctx);

        assertThat(r.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("truncated")).isEqualTo(true);
        assertThat(((String) out.get("text"))).contains("[... truncated");
    }

    // ─── CR-100: content(base64) 분기 (로컬 PC 파싱 다리) ───────────────────

    @Test
    void validate_content_ok() {
        String b64 = java.util.Base64.getEncoder().encodeToString("hello".getBytes());
        ValidationResult r = tool.validateInput(Map.of("content", b64, "file_type", "txt"), ctx);
        assertThat(r.valid()).isTrue();
    }

    @Test
    void validate_contentAndUrlBoth_fails() {
        String b64 = java.util.Base64.getEncoder().encodeToString("hello".getBytes());
        ValidationResult r = tool.validateInput(
                Map.of("content", b64, "url", "https://example.com/a.docx"), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_contentInvalidBase64_fails() {
        ValidationResult r = tool.validateInput(Map.of("content", "!!!not base64!!!"), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void execute_contentDelegatesToSidecarWithBase64() {
        String b64 = java.util.Base64.getEncoder().encodeToString("hello docx bytes".getBytes());
        when(ragClient.parseDocument(eq(b64), eq("docx")))
                .thenReturn(Map.of(
                        "text", "parsed local docx",
                        "metadata", Map.of("file_type", "docx")
                ));

        ToolResult r = tool.execute(Map.of("content", b64, "file_type", "docx"), ctx);

        assertThat(r.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("text")).isEqualTo("parsed local docx");
        assertThat(out.get("source")).isEqualTo("content:base64");
        verify(ragClient).parseDocument(eq(b64), eq("docx"));
    }

    @Test
    void execute_sidecarExceptionReturnsError() {
        when(ragClient.callToolRaw(eq("parse_document"), anyMap()))
                .thenThrow(new RuntimeException("sidecar down"));

        ToolResult r = tool.execute(Map.of("url", "https://example.com/x.docx"), ctx);

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("sidecar down");
    }

    // ─── CR-107: url 갈래 PDF 비전 통일 (사이드카 텍스트 추출로 새지 않도록) ──────────
    // downloadBytes 가 실제 HttpClient 를 쓰므로 로컬 HttpServer 로 url 다운로드를 실제 검증한다.

    @Test
    void execute_urlPdfWithExtension_goesToVisionNotSidecar() throws Exception {
        // 작은 유효 PDF 바이트 (≤3MB → INLINE_PDF 비전 경로). 사이드카 텍스트 추출은 절대 호출되면 안 됨.
        byte[] pdf = minimalPdfBytes();
        try (LocalServer srv = LocalServer.serving("/a.pdf", "application/pdf", pdf)) {
            ToolResult r = tool.execute(Map.of("url", srv.url("/a.pdf")), ctx);

            assertThat(r.success()).isTrue();
            Map<?, ?> out = (Map<?, ?>) r.output();
            assertThat(out.get("mode")).isEqualTo("inline_pdf");   // 비전 경로
            // 비전 경로는 사이드카 parse_document 를 거치지 않는다 (이게 CR-107 의 핵심).
            verify(ragClient, never()).callToolRaw(eq("parse_document"), anyMap());
            verify(ragClient, never()).parseDocument(anyString(), anyString());
        }
    }

    @Test
    void execute_urlPdfWithoutExtension_detectedByMagicAndGoesToVision() throws Exception {
        // .../download 처럼 확장자 없는 url — 매직넘버(%PDF)로 PDF 판정 → 비전.
        byte[] pdf = minimalPdfBytes();
        try (LocalServer srv = LocalServer.serving("/download", "application/pdf", pdf)) {
            ToolResult r = tool.execute(Map.of("url", srv.url("/download")), ctx);

            assertThat(r.success()).isTrue();
            Map<?, ?> out = (Map<?, ?>) r.output();
            assertThat(out.get("mode")).isEqualTo("inline_pdf");
            verify(ragClient, never()).callToolRaw(eq("parse_document"), anyMap());
            verify(ragClient, never()).parseDocument(anyString(), anyString());
        }
    }

    @Test
    void execute_urlNonPdfWithoutExtension_fallsBackToSidecarTextExtraction() throws Exception {
        // 확장자 없고 PDF 도 아님(매직넘버 불일치) → 이미 받은 바이트를 base64 로 사이드카 텍스트 추출.
        byte[] docxLike = "PK not-a-pdf zip-ish bytes".getBytes();
        when(ragClient.parseDocument(anyString(), eq("")))
                .thenReturn(Map.of("text", "extracted text", "metadata", Map.of("file_type", "docx")));

        try (LocalServer srv = LocalServer.serving("/download", "application/octet-stream", docxLike)) {
            ToolResult r = tool.execute(Map.of("url", srv.url("/download")), ctx);

            assertThat(r.success()).isTrue();
            Map<?, ?> out = (Map<?, ?>) r.output();
            assertThat(out.get("text")).isEqualTo("extracted text");
            verify(ragClient).parseDocument(anyString(), eq(""));   // 사이드카 경로 사용
        }
    }

    /** 1페이지 최소 유효 PDF (pdfplumber/poppler 가 열 수 있는 수준은 아니어도 INLINE_PDF 게이트는 바이트크기+%PDF 만 본다). */
    private static byte[] minimalPdfBytes() {
        String pdf = "%PDF-1.4\n"
                + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
                + "xref\n0 4\n0000000000 65535 f \n"
                + "trailer<</Root 1 0 R/Size 4>>\nstartxref\n0\n%%EOF";
        return pdf.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** 테스트용 단발 HTTP 서버 — downloadBytes 의 실제 GET 을 받아준다. */
    private static final class LocalServer implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private LocalServer(com.sun.net.httpserver.HttpServer server) { this.server = server; }

        static LocalServer serving(String path, String contentType, byte[] body) throws Exception {
            com.sun.net.httpserver.HttpServer s =
                    com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext(path, exchange -> {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, body.length);
                try (var os = exchange.getResponseBody()) { os.write(body); }
            });
            s.start();
            return new LocalServer(s);
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override public void close() { server.stop(0); }
    }
}
