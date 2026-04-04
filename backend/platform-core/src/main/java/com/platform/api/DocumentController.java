package com.platform.api;

import com.platform.rag.MCPRagClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 문서 생성 API 컨트롤러 (CR-018).
 *
 * LLM이 생성한 코드를 Python 사이드카에서 실행하여 문서 파일을 생성.
 * 템플릿 저장/조회/렌더링 지원.
 * 지원: PPTX, DOCX, PDF, XLSX, CSV, HTML, PNG, JPG, SVG
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Document Generation", description = "AI 문서 생성 (CR-018)")
public class DocumentController {

    private final MCPRagClient mcpRagClient;

    public DocumentController(MCPRagClient mcpRagClient) {
        this.mcpRagClient = mcpRagClient;
    }

    @PostMapping("/generate")
    @Operation(summary = "문서 생성 (LLM 코드 실행)")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest request) {
        Map<String, Object> result = mcpRagClient.callToolRaw("generate_document", Map.of(
                "code", request.code(),
                "output_format", request.outputFormat() != null ? request.outputFormat() : "pptx",
                "output_filename", request.outputFilename() != null ? request.outputFilename() : ""
        ));

        if (Boolean.TRUE.equals(result.get("success"))) {
            String base64Content = (String) result.get("file_base64");
            String filename = (String) result.get("filename");
            String format = (String) result.get("format");

            byte[] fileBytes = Base64.getDecoder().decode(base64Content);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(getMediaType(format));
            headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(fileBytes.length);

            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        }

        return ResponseEntity.badRequest().body(ApiResponse.ok(result));
    }

    @PostMapping("/generate-json")
    @Operation(summary = "문서 생성 (JSON 응답, base64)")
    public ApiResponse<Map<String, Object>> generateJson(@RequestBody GenerateRequest request) {
        Map<String, Object> result = mcpRagClient.callToolRaw("generate_document", Map.of(
                "code", request.code(),
                "output_format", request.outputFormat() != null ? request.outputFormat() : "pptx",
                "output_filename", request.outputFilename() != null ? request.outputFilename() : ""
        ));
        return ApiResponse.ok(result);
    }

    @GetMapping("/formats")
    @Operation(summary = "지원 포맷 목록")
    public ApiResponse<Map<String, Object>> listFormats() {
        Map<String, Object> result = mcpRagClient.callToolRaw("list_document_formats", Map.of());
        return ApiResponse.ok(result);
    }

    // ── 템플릿 API ──────────────────────────────────────────────

    @PostMapping("/templates")
    @Operation(summary = "문서 템플릿 저장")
    public ApiResponse<Map<String, Object>> saveTemplate(@RequestBody TemplateRequest request) {
        Map<String, Object> input = new HashMap<>();
        input.put("name", request.name());
        input.put("format", request.format() != null ? request.format() : "pptx");
        input.put("template_type", request.templateType() != null ? request.templateType() : "code");
        input.put("code_template", request.codeTemplate() != null ? request.codeTemplate() : "");
        input.put("variables", request.variables() != null ? request.variables() : "[]");
        input.put("description", request.description() != null ? request.description() : "");
        input.put("tags", request.tags() != null ? request.tags() : "[]");
        input.put("created_by", request.createdBy() != null ? request.createdBy() : "");
        Map<String, Object> result = mcpRagClient.callToolRaw("save_document_template", input);
        return ApiResponse.ok(result);
    }

    @GetMapping("/templates")
    @Operation(summary = "문서 템플릿 목록 조회")
    public ApiResponse<Map<String, Object>> listTemplates(
            @RequestParam(defaultValue = "") String format,
            @RequestParam(defaultValue = "") String templateType) {
        Map<String, Object> input = new HashMap<>();
        input.put("format", format);
        input.put("template_type", templateType);
        Map<String, Object> result = mcpRagClient.callToolRaw("list_document_templates", input);
        return ApiResponse.ok(result);
    }

    @GetMapping("/templates/{templateId}")
    @Operation(summary = "문서 템플릿 상세 조회")
    public ApiResponse<Map<String, Object>> getTemplate(@PathVariable String templateId) {
        Map<String, Object> result = mcpRagClient.callToolRaw("get_document_template",
                Map.of("template_id", templateId));
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/templates/{templateId}")
    @Operation(summary = "문서 템플릿 삭제")
    public ApiResponse<Map<String, Object>> deleteTemplate(@PathVariable String templateId) {
        Map<String, Object> result = mcpRagClient.callToolRaw("delete_document_template",
                Map.of("template_id", templateId));
        return ApiResponse.ok(result);
    }

    @PostMapping("/templates/{templateId}/render")
    @Operation(summary = "템플릿으로 문서 생성")
    public ResponseEntity<?> renderTemplate(
            @PathVariable String templateId,
            @RequestBody RenderRequest request) {
        Map<String, Object> input = new HashMap<>();
        input.put("template_id", templateId);
        input.put("variables", request.variables() != null ? request.variables() : "{}");
        input.put("output_format", request.outputFormat() != null ? request.outputFormat() : "");
        Map<String, Object> result = mcpRagClient.callToolRaw("render_document_template", input);

        if (Boolean.TRUE.equals(result.get("success")) && result.containsKey("file_base64")) {
            String base64Content = (String) result.get("file_base64");
            String filename = (String) result.get("filename");
            String fmt = (String) result.get("format");

            byte[] fileBytes = Base64.getDecoder().decode(base64Content);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(getMediaType(fmt));
            headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(fileBytes.length);
            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        }

        return ResponseEntity.badRequest().body(ApiResponse.ok(result));
    }

    @PostMapping("/templates/{templateId}/render-json")
    @Operation(summary = "템플릿으로 문서 생성 (JSON 응답)")
    public ApiResponse<Map<String, Object>> renderTemplateJson(
            @PathVariable String templateId,
            @RequestBody RenderRequest request) {
        Map<String, Object> input = new HashMap<>();
        input.put("template_id", templateId);
        input.put("variables", request.variables() != null ? request.variables() : "{}");
        input.put("output_format", request.outputFormat() != null ? request.outputFormat() : "");
        Map<String, Object> result = mcpRagClient.callToolRaw("render_document_template", input);
        return ApiResponse.ok(result);
    }

    @PostMapping(value = "/templates/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "파일 업로드로 템플릿 등록 (PPTX/DOCX)")
    public ApiResponse<Map<String, Object>> uploadTemplate(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "[]") String tags) throws Exception {

        String originalName = file.getOriginalFilename();
        String ext = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase() : "";

        if (!ext.equals("pptx") && !ext.equals("docx") && !ext.equals("pdf")) {
            return ApiResponse.ok(Map.of("success", false,
                    "error", "Only PPTX, DOCX, and PDF files are supported for file templates"));
        }

        String fileBase64 = Base64.getEncoder().encodeToString(file.getBytes());
        String templateName = name.isBlank() ? originalName : name;

        Map<String, Object> input = new HashMap<>();
        input.put("name", templateName);
        input.put("format", ext);
        input.put("file_base64", fileBase64);
        input.put("original_filename", originalName);
        input.put("description", description);
        input.put("tags", tags);

        Map<String, Object> result = mcpRagClient.callToolRaw("upload_file_template", input);
        return ApiResponse.ok(result);
    }

    // ── CR-013: 스키마 기반 문서 생성 ─────────────────────────────

    @PostMapping("/schema/generate")
    @Operation(summary = "스키마 기반 문서 생성 (CR-013)")
    public ResponseEntity<?> generateFromSchema(@RequestBody SchemaGenerateRequest request) {
        Map<String, Object> input = new HashMap<>();
        input.put("schema", request.schema());
        input.put("output_format", request.outputFormat() != null ? request.outputFormat() : "pptx");
        input.put("theme_name", request.themeName() != null ? request.themeName() : "default");
        input.put("custom_theme", request.customTheme() != null ? request.customTheme() : "{}");
        input.put("output_filename", request.outputFilename() != null ? request.outputFilename() : "");

        Map<String, Object> result = mcpRagClient.callToolRaw("generate_document_from_schema", input);

        if (Boolean.TRUE.equals(result.get("success"))) {
            String base64Content = (String) result.get("file_base64");
            String filename = (String) result.get("filename");
            String format = (String) result.get("format");

            byte[] fileBytes = Base64.getDecoder().decode(base64Content);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(getMediaType(format));
            headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(fileBytes.length);
            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        }

        return ResponseEntity.badRequest().body(ApiResponse.ok(result));
    }

    @PostMapping("/schema/generate-json")
    @Operation(summary = "스키마 기반 문서 생성 (JSON 응답)")
    public ApiResponse<Map<String, Object>> generateFromSchemaJson(@RequestBody SchemaGenerateRequest request) {
        Map<String, Object> input = new HashMap<>();
        input.put("schema", request.schema());
        input.put("output_format", request.outputFormat() != null ? request.outputFormat() : "pptx");
        input.put("theme_name", request.themeName() != null ? request.themeName() : "default");
        input.put("custom_theme", request.customTheme() != null ? request.customTheme() : "{}");
        input.put("output_filename", request.outputFilename() != null ? request.outputFilename() : "");

        Map<String, Object> result = mcpRagClient.callToolRaw("generate_document_from_schema", input);
        return ApiResponse.ok(result);
    }

    @PostMapping("/schema/validate")
    @Operation(summary = "문서 스키마 검증")
    public ApiResponse<Map<String, Object>> validateSchema(@RequestBody String schemaJson) {
        Map<String, Object> result = mcpRagClient.callToolRaw("validate_document_schema",
                Map.of("schema", schemaJson));
        return ApiResponse.ok(result);
    }

    @GetMapping("/themes")
    @Operation(summary = "문서 테마 프리셋 목록 (CR-013)")
    public ApiResponse<Map<String, Object>> listThemes() {
        Map<String, Object> result = mcpRagClient.callToolRaw("list_document_themes", Map.of());
        return ApiResponse.ok(result);
    }

    // ── Private ──────────────────────────────────────────────────

    private MediaType getMediaType(String format) {
        return switch (format) {
            case "pptx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "docx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "xlsx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "csv" -> MediaType.parseMediaType("text/csv");
            case "html" -> MediaType.TEXT_HTML;
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "svg" -> MediaType.parseMediaType("image/svg+xml");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    public record GenerateRequest(
            String code,
            String outputFormat,
            String outputFilename
    ) {}

    public record TemplateRequest(
            String name,
            String format,
            String templateType,
            String codeTemplate,
            String variables,
            String description,
            String tags,
            String createdBy
    ) {}

    public record RenderRequest(
            String variables,
            String outputFormat
    ) {}

    public record SchemaGenerateRequest(
            String schema,
            String outputFormat,
            String themeName,
            String customTheme,
            String outputFilename
    ) {}
}
