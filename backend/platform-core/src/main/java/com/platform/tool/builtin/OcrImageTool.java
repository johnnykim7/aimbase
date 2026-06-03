package com.platform.tool.builtin;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-092: 전통적 OCR (Tesseract) 도구.
 *
 * 단일 이미지(JPG/PNG/etc.) base64 → Tesseract OCR 텍스트 추출.
 * Vision 모델(Claude/GPT-4o) 우회와 분리된 결정론·오프라인 경로.
 *
 * 사이드카 ocr_image MCP 툴을 BE Tool 인터페이스로 노출.
 * McpExposurePolicy CLI 화이트리스트에 등록 → Claude CLI 자율 호출 가능.
 */
@Component
public class OcrImageTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(OcrImageTool.class);
    private static final int MAX_INPUT_SIZE_BYTES = 20 * 1024 * 1024; // 20MB base64 (~15MB raw)

    private final MCPRagClient ragClient;
    private final String defaultLanguages;

    public OcrImageTool(MCPRagClient ragClient,
                        @Value("${aimbase.ocr.languages:kor+eng}") String defaultLanguages) {
        this.ragClient = ragClient;
        this.defaultLanguages = defaultLanguages;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "ocr_image",
                "Extract text from a single image (JPG/PNG/etc.) using Tesseract OCR (CR-092). " +
                        "Deterministic and offline. For multilingual scans, separate language codes with '+' (e.g. 'kor+eng'). " +
                        "Use this when you need traditional OCR rather than Vision-model interpretation.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_base64", Map.of(
                                        "type", "string",
                                        "description", "Base64-encoded image bytes"
                                ),
                                "languages", Map.of(
                                        "type", "string",
                                        "description", "Tesseract language codes joined with '+'. " +
                                                "Allowed: eng, kor, jpn, chi_sim, chi_tra, fra, deu, spa. " +
                                                "Default: " + defaultLanguages
                                )
                        ),
                        "required", List.of("file_base64")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "ocr_image", "1.0", ToolScope.BUILTIN,
                PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE,
                List.of("ocr", "image", "tesseract"),
                List.of("read", "extract")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        Object fileBase64 = input.get("file_base64");
        if (!(fileBase64 instanceof String s) || s.isBlank()) {
            return ValidationResult.fail("file_base64 is required.");
        }
        if (s.length() > MAX_INPUT_SIZE_BYTES) {
            return ValidationResult.fail("file_base64 too large (max ~20MB encoded).");
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String fileBase64 = (String) input.get("file_base64");
        String languages = (String) input.getOrDefault("languages", defaultLanguages);

        try {
            Map<String, Object> result = ragClient.ocrImage(fileBase64, languages);

            if (!Boolean.TRUE.equals(result.get("success"))) {
                String err = String.valueOf(result.getOrDefault("error", "unknown_error"));
                log.warn("ocr_image failed (lang={}): {}", languages, err);
                return ToolResult.error("OCR failed: " + err)
                        .withDuration(System.currentTimeMillis() - start);
            }

            String text = result.get("text") instanceof String s ? s : "";
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("text", text);
            output.put("languages", languages);
            output.put("character_count", text.length());

            String summary = "OCR extracted " + text.length() + " chars (lang=" + languages + ")";
            return new ToolResult(true, output, summary,
                    List.of(), List.of(),
                    Map.of("languages", languages, "characterCount", text.length()),
                    null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("ocr_image threw: {}", e.getMessage());
            return ToolResult.error("OCR call failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }
}
