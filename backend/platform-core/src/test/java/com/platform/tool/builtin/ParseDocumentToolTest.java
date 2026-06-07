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
                new com.platform.attachment.PdfVisionResolver(ragClient, 3145728L, 20971520L, 20, 100);
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
}
