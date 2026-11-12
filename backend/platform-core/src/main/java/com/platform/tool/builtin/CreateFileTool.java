package com.platform.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.config.WorkspaceProperties;
import com.platform.document.DocModel;
import com.platform.document.DocumentRenderException;
import com.platform.document.DocumentRenderer;
import com.platform.document.SlideFileWriter;
import com.platform.document.TableFileWriter;
import com.platform.service.PromptTemplateService;
import com.platform.session.ChatWorkspaceAssigner;
import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.McpExposureLevel;
import com.platform.tool.PermissionLevel;
import com.platform.tool.RetryPolicy;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolResult;
import com.platform.tool.ToolScope;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 파일 만들기 서버 도구(CR-182 3.2, BIZ-210). 서버가 그려 세션 작업 폴더의 out/ 에 쓴다.
 *
 * <p>API 연결은 도구 고리로, CLI 연결은 서버 MCP 로 같은 도구를 부른다. 서버가 쓰므로 실행기가 다른
 * 장비에 있어도 파일이 위젯 내려받기 목록(CR-132)에 닿는다.
 */
@Component
public class CreateFileTool implements EnhancedToolExecutor {

    public static final String NAME = "create_file";
    public static final String MAX_BYTES_KEY = "chat.file-create.max-bytes";
    static final String DESCRIPTION_KEY = "tool.create-file.description";
    public static final String SOURCE_DIR = ".source";

    static final String ERR_DISABLED = "file_create_disabled";
    static final String ERR_FORMAT = "file_invalid:format";
    static final String ERR_CONTENT = "file_invalid:content";
    static final String ERR_FILE_NAME = "file_invalid:file_name";
    static final String ERR_TOO_LARGE = "file_too_large";
    static final String ERR_NO_WORKSPACE = "file_no_workspace";

    private static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_NAME_CHARS = 120;
    private static final List<String> FORMATS = List.of("docx", "pdf", "xlsx", "pptx", "csv", "md", "html", "txt");
    private static final String FALLBACK_DESCRIPTION =
            "Create a downloadable file (docx, pdf, xlsx, pptx, csv, md, html, txt) when the user asks for one.";

    private static final Logger log = LoggerFactory.getLogger(CreateFileTool.class);

    private final DocumentRenderer renderer;
    private final ChatWorkspaceAssigner workspaceAssigner;
    private final WorkspaceProperties workspaceProperties;
    private final PlatformSettingsService settings;
    private final PromptTemplateService promptTemplates;
    private final ObjectMapper objectMapper;

    public CreateFileTool(DocumentRenderer renderer, ChatWorkspaceAssigner workspaceAssigner,
                          WorkspaceProperties workspaceProperties, PlatformSettingsService settings,
                          PromptTemplateService promptTemplates, ObjectMapper objectMapper) {
        this.renderer = renderer;
        this.workspaceAssigner = workspaceAssigner;
        this.workspaceProperties = workspaceProperties;
        this.settings = settings;
        this.promptTemplates = promptTemplates;
        this.objectMapper = objectMapper;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        Map<String, Object> cellType = Map.of("type", List.of("string", "number", "boolean", "null"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_name", Map.of("type", "string", "description", "File name without extension."));
        props.put("format", Map.of("type", "string", "enum", FORMATS));
        props.put("title", Map.of("type", "string",
                "description", "Top heading for docx, pdf, html. Cover slide for pptx."));
        props.put("content", Map.of("type", "string",
                "description", "Markdown body for docx, pdf, html. Text for md, txt, csv."));
        props.put("sheets", Map.of("type", "array", "description", "Tables for xlsx and csv.",
                "items", Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "rows", Map.of("type", "array", "items", Map.of("type", "array", "items", cellType))),
                        "required", List.of("rows"))));
        props.put("slides", Map.of("type", "array", "description", "Slides for pptx.",
                "items", Map.of("type", "object", "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "bullets", Map.of("type", "array", "items", Map.of("type", "string")),
                        "notes", Map.of("type", "string", "description", "Speaker notes.")),
                        "required", List.of("title"))));
        props.put("overwrite", Map.of("type", "boolean",
                "description", "Replace a file with the same name made earlier in this conversation."));
        return new UnifiedToolDef(NAME,
                promptTemplates.getTemplateOrFallback(DESCRIPTION_KEY, FALLBACK_DESCRIPTION),
                Map.of("type", "object", "properties", props, "required", List.of("file_name", "format")));
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                NAME, "1.0", ToolScope.NATIVE, PermissionLevel.RESTRICTED_WRITE,
                false, false, false, false,
                RetryPolicy.NONE, List.of("file", "document"), List.of("write"),
                McpExposureLevel.CLI);
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        if (!Boolean.parseBoolean(settings.getString(com.platform.orchestrator.FileCreateToolGate.ENABLED_KEY, "true"))) {
            return ToolResult.error(ERR_DISABLED);
        }
        String format = str(input.get("format")).toLowerCase(Locale.ROOT).strip();
        if (!FORMATS.contains(format)) {
            return ToolResult.error(ERR_FORMAT + ": use one of " + String.join(", ", FORMATS));
        }
        String baseName = cleanName(str(input.get("file_name")), format);
        if (baseName.isEmpty()) {
            return ToolResult.error(ERR_FILE_NAME + ": file_name is empty after removing path characters");
        }

        byte[] bytes;
        try {
            bytes = render(format, input);
        } catch (InvalidContent e) {
            return ToolResult.error(ERR_CONTENT + ": " + e.getMessage());
        } catch (DocumentRenderException e) {
            return ToolResult.error(e.getCode());
        }
        long max = settings.getLong(MAX_BYTES_KEY, DEFAULT_MAX_BYTES);
        if (bytes.length > max) {
            return ToolResult.error(ERR_TOO_LARGE + ": " + bytes.length + " bytes, limit " + max);
        }

        Path workspace = workspace(ctx);
        if (workspace == null) {
            return ToolResult.error(ERR_NO_WORKSPACE);
        }
        boolean overwrite = Boolean.TRUE.equals(input.get("overwrite"))
                || "true".equalsIgnoreCase(str(input.get("overwrite")));
        try {
            Path outDir = workspace.resolve("out");
            Files.createDirectories(outDir);
            String fileName = write(outDir, baseName, format, bytes, overwrite);
            saveSource(outDir, fileName, input);
            log.info("[CR182] 파일 만듦 session={} file={} size={}", ctx.sessionId(), fileName, bytes.length);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file_name", fileName);
            out.put("size", bytes.length);
            return ToolResult.ok(out, "Created " + fileName);
        } catch (IOException e) {
            log.warn("[CR182] 파일 쓰기 실패 workspace={}: {}", workspace, e.getMessage());
            return ToolResult.error("file_write_failed: " + e.getMessage());
        }
    }

    private byte[] render(String format, Map<String, Object> input) {
        String title = blankToNull(str(input.get("title")));
        String content = str(input.get("content"));
        return switch (format) {
            case "docx" -> renderer.docx(DocModel.single(title, requireText(content, format)));
            case "pdf" -> renderer.pdf(DocModel.single(title, requireText(content, format)));
            case "html" -> renderer.html(DocModel.single(title, requireText(content, format)))
                    .getBytes(StandardCharsets.UTF_8);
            case "md", "txt" -> requireText(content, format).getBytes(StandardCharsets.UTF_8);
            case "xlsx" -> TableFileWriter.xlsx(sheets(input.get("sheets"), format));
            case "csv" -> {
                List<TableFileWriter.SheetData> sheets = optionalSheets(input.get("sheets"));
                if (!sheets.isEmpty()) yield TableFileWriter.csv(sheets.get(0).rows());
                yield TableFileWriter.withBom(requireText(content, format));
            }
            case "pptx" -> SlideFileWriter.pptx(title, slides(input.get("slides")));
            default -> throw new InvalidContent("unknown format " + format);
        };
    }

    /** 실행 문맥의 작업 폴더. 없으면 세션 작업 폴더를 잡는다. 허용 폴더 밖이면 쓰지 않는다. */
    private Path workspace(ToolContext ctx) {
        String path = ctx == null ? null : ctx.workspacePath();
        if ((path == null || path.isBlank()) && ctx != null) {
            path = workspaceAssigner.assign(ctx.sessionId());
        }
        if (path == null || path.isBlank()) return null;
        Path dir = Path.of(path).toAbsolutePath().normalize();
        if (!workspaceProperties.isInsideWhitelist(dir)) {
            log.warn("[CR182] 파일 만들기 거절 — 허용 폴더 밖: {}", dir);
            return null;
        }
        return dir;
    }

    /**
     * 임시 파일에 쓴 뒤 옮긴다. 같은 이름이 있고 overwrite 가 아니면 "이름 (2).xlsx" 처럼 번호를 붙인다.
     * 옮기기가 이름 충돌을 원자적으로 알려 줘서, 같은 이름을 동시에 만들어도 서로 덮지 않는다.
     */
    private static String write(Path outDir, String baseName, String ext, byte[] bytes, boolean overwrite)
            throws IOException {
        Path tmp = Files.createTempFile(outDir, ".create-", ".tmp");
        try {
            Files.write(tmp, bytes);
            String name = baseName + "." + ext;
            if (overwrite) {
                Files.move(tmp, outDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                return name;
            }
            for (int n = 1; ; n++) {
                name = n == 1 ? baseName + "." + ext : baseName + " (" + n + ")." + ext;
                try {
                    Files.move(tmp, outDir.resolve(name));
                    return name;
                } catch (FileAlreadyExistsException e) {
                    if (n >= 1000) throw e;
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 원본은 CR-181 이 옆 창 문서를 대화로 고칠 때 읽는다. 위젯 목록에는 나오지 않는다(점 폴더). */
    private void saveSource(Path outDir, String fileName, Map<String, Object> input) {
        try {
            Path dir = outDir.resolve(SOURCE_DIR);
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName + ".json"), objectMapper.writeValueAsBytes(input));
        } catch (IOException e) {
            log.warn("[CR182] 원본 저장 실패 {}: {}", fileName, e.getMessage());
        }
    }

    public static String cleanName(String raw, String ext) {
        StringBuilder sb = new StringBuilder();
        raw.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp == 0x7F || "/\\:*?\"<>|".indexOf(cp) >= 0) return;
            sb.appendCodePoint(cp);
        });
        String name = sb.toString().strip();
        String suffix = "." + ext;
        if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            name = name.substring(0, name.length() - suffix.length()).strip();
        }
        while (name.startsWith(".")) name = name.substring(1).strip();
        if (name.codePointCount(0, name.length()) > MAX_NAME_CHARS) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME_CHARS));
        }
        while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
        return name;
    }

    private static String requireText(String content, String format) {
        if (content == null || content.isBlank()) throw new InvalidContent(format + " needs content");
        return content;
    }

    private static List<TableFileWriter.SheetData> sheets(Object raw, String format) {
        List<TableFileWriter.SheetData> sheets = optionalSheets(raw);
        if (sheets.isEmpty()) throw new InvalidContent(format + " needs sheets with rows");
        return sheets;
    }

    private static List<TableFileWriter.SheetData> optionalSheets(Object raw) {
        List<TableFileWriter.SheetData> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            List<List<Object>> rows = new ArrayList<>();
            if (m.get("rows") instanceof List<?> rawRows) {
                for (Object r : rawRows) {
                    if (r instanceof List<?> cells) rows.add(new ArrayList<>(cells));
                    else if (r != null) rows.add(List.of(r));
                }
            }
            if (!rows.isEmpty()) out.add(new TableFileWriter.SheetData(blankToNull(str(m.get("name"))), rows));
        }
        return out;
    }

    private static List<SlideFileWriter.SlideData> slides(Object raw) {
        List<SlideFileWriter.SlideData> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                List<String> bullets = new ArrayList<>();
                if (m.get("bullets") instanceof List<?> bs) {
                    for (Object b : bs) if (b != null && !str(b).isBlank()) bullets.add(str(b));
                }
                out.add(new SlideFileWriter.SlideData(str(m.get("title")), bullets, blankToNull(str(m.get("notes")))));
            }
        }
        if (out.isEmpty()) throw new InvalidContent("pptx needs slides");
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 형식에 필요한 칸이 없거나 비었다. */
    private static final class InvalidContent extends RuntimeException {
        InvalidContent(String message) {
            super(message);
        }
    }
}
