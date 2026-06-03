package com.platform.tool.builtin;

import com.platform.rag.MCPRagClient;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import com.platform.tool.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CR-092 OcrImageTool — sidecar ocr_image MCP 툴 BE 래퍼.
 */
@ExtendWith(MockitoExtension.class)
class OcrImageToolTest {

    @Mock private MCPRagClient ragClient;

    private OcrImageTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new OcrImageTool(ragClient, "kor+eng");
        ctx = ToolContext.minimal("test-tenant", "test-session");
    }

    @Test
    void definition_hasNameAndSchema() {
        var def = tool.getDefinition();
        assertThat(def.name()).isEqualTo("ocr_image");
        assertThat(def.description()).contains("Tesseract");
        assertThat(def.inputSchema()).containsKey("properties");
    }

    @Test
    void validate_missingFileBase64_fails() {
        ValidationResult r = tool.validateInput(Map.of(), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_blankFileBase64_fails() {
        ValidationResult r = tool.validateInput(Map.of("file_base64", "   "), ctx);
        assertThat(r.valid()).isFalse();
    }

    @Test
    void validate_ok_passes() {
        ValidationResult r = tool.validateInput(
                Map.of("file_base64", "iVBORw0KGgo="), ctx);
        assertThat(r.valid()).isTrue();
    }

    @Test
    void execute_successMapsTextAndCounts() {
        when(ragClient.ocrImage(anyString(), anyString()))
                .thenReturn(Map.of(
                        "success", true,
                        "text", "안녕하세요 hello",
                        "languages", "kor+eng"
                ));

        ToolResult r = tool.execute(
                Map.of("file_base64", "iVBORw0KGgo="),
                ctx);

        assertThat(r.success()).isTrue();
        assertThat(r.output()).isInstanceOf(Map.class);
        Map<?, ?> out = (Map<?, ?>) r.output();
        assertThat(out.get("text")).isEqualTo("안녕하세요 hello");
        assertThat(out.get("languages")).isEqualTo("kor+eng");
        assertThat(out.get("character_count")).isEqualTo("안녕하세요 hello".length());
    }

    @Test
    void execute_failureReturnsError() {
        when(ragClient.ocrImage(anyString(), anyString()))
                .thenReturn(Map.of(
                        "success", false,
                        "error", "tesseract_not_installed"
                ));

        ToolResult r = tool.execute(
                Map.of("file_base64", "iVBORw0KGgo="),
                ctx);

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("tesseract_not_installed");
    }

    @Test
    void execute_exceptionReturnsError() {
        when(ragClient.ocrImage(anyString(), anyString()))
                .thenThrow(new RuntimeException("MCP down"));

        ToolResult r = tool.execute(
                Map.of("file_base64", "iVBORw0KGgo="),
                ctx);

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).contains("MCP down");
    }

    @Test
    void execute_usesDefaultLanguagesWhenNotSpecified() {
        when(ragClient.ocrImage(anyString(), anyString()))
                .thenReturn(Map.of("success", true, "text", "x"));

        ToolResult r = tool.execute(Map.of("file_base64", "iVBORw0KGgo="), ctx);
        assertThat(r.success()).isTrue();
    }
}
