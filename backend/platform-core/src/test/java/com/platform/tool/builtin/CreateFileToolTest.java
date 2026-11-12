package com.platform.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.artifact.ArtifactService;
import com.platform.config.PlatformSettingsService;
import com.platform.config.WorkspaceProperties;
import com.platform.document.DocumentFonts;
import com.platform.document.DocumentRenderer;
import com.platform.document.TestFonts;
import com.platform.orchestrator.FileCreateToolGate;
import com.platform.service.PromptTemplateService;
import com.platform.session.ChatWorkspaceAssigner;
import com.platform.session.SessionStore;
import com.platform.tool.ApprovalState;
import com.platform.tool.PermissionLevel;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-182 TC-CHT-081, TC-CHT-082, TC-CHT-083(설정 꺼짐): 파일 만들기 도구. */
class CreateFileToolTest {

    @TempDir Path root;

    private Path ws;
    private PlatformSettingsService settings;
    private ChatWorkspaceAssigner assigner;
    private WorkspaceProperties props;
    private DocumentFonts fonts;
    private CreateFileTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        ws = Files.createDirectories(root.resolve("ws"));
        settings = mock(PlatformSettingsService.class);
        // 저장된 설정이 없을 때처럼 코드 기본값을 돌려준다.
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        assigner = mock(ChatWorkspaceAssigner.class);
        props = new WorkspaceProperties();
        props.setWhitelistRoots(List.of(root.toString()));
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        when(prompts.getTemplateOrFallback(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        fonts = TestFonts.find();
        tool = new CreateFileTool(new DocumentRenderer(fonts), assigner, props, settings, prompts, new ObjectMapper());
        ctx = context(ws.toString());
    }

    @Test
    @DisplayName("TC-CHT-081 docx 는 POI 로 다시 열리고 본문이 들어 있다")
    void docx_reopens() throws IOException {
        ToolResult r = tool.execute(input("보고서", "docx", "content", "## 요약\n\n- 반품 120건"), ctx);

        assertThat(r.success()).as(r.summary()).isTrue();
        try (InputStream in = Files.newInputStream(out("보고서.docx")); XWPFDocument doc = new XWPFDocument(in)) {
            assertThat(new XWPFWordExtractor(doc).getText()).contains("요약").contains("반품 120건");
        }
    }

    @Test
    @DisplayName("TC-CHT-081 pdf 는 PDFBox 로 다시 열리고 한글이 나온다")
    void pdf_reopens() throws IOException {
        assumeTrue(fonts.available(), "한글 TTF 폴더가 없어 PDF 확인을 건너뛴다");

        ToolResult r = tool.execute(input("보고서", "pdf", "content", "## 요약\n\n반품 120건"), ctx);

        assertThat(r.success()).as(r.summary()).isTrue();
        try (PDDocument pdf = Loader.loadPDF(out("보고서.pdf").toFile())) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("요약");
        }
    }

    @Test
    @DisplayName("TC-CHT-081 xlsx 는 숫자 글 12 를 숫자 칸으로 쓴다")
    void xlsx_numeric_text_becomes_numeric_cell() throws IOException {
        Map<String, Object> in = input("현황", "xlsx");
        in.put("sheets", List.of(Map.of("name", "반품", "rows", List.of(
                List.of("구분", "건수"),
                List.of("파손", "12")))));

        ToolResult r = tool.execute(in, ctx);

        assertThat(r.success()).as(r.summary()).isTrue();
        try (InputStream is = Files.newInputStream(out("현황.xlsx")); XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("반품");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("건수");
            Cell cell = sheet.getRow(1).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell.getNumericCellValue()).isEqualTo(12.0);
        }
    }

    @Test
    @DisplayName("TC-CHT-081 pptx 는 표지와 두 장으로 3장이고 메모가 남는다")
    void pptx_has_cover_two_slides_and_notes() throws IOException {
        Map<String, Object> in = input("발표", "pptx");
        in.put("title", "3분기 반품");
        in.put("slides", List.of(
                Map.of("title", "현황", "bullets", List.of("파손 40", "변심 80"), "notes", "말할 것"),
                Map.of("title", "대책", "bullets", List.of("포장 보강"))));

        ToolResult r = tool.execute(in, ctx);

        assertThat(r.success()).as(r.summary()).isTrue();
        try (InputStream is = Files.newInputStream(out("발표.pptx")); XMLSlideShow ppt = new XMLSlideShow(is)) {
            List<XSLFSlide> slides = ppt.getSlides();
            assertThat(slides).hasSize(3);
            assertThat(shapesText(slides.get(0).getShapes())).contains("3분기 반품");
            assertThat(shapesText(slides.get(1).getShapes())).contains("현황").contains("파손 40");
            assertThat(slides.get(1).getNotes()).isNotNull();
            assertThat(shapesText(slides.get(1).getNotes().getShapes())).contains("말할 것");
        }
    }

    @Test
    @DisplayName("TC-CHT-081 csv 는 앞에 UTF-8 BOM 이 붙는다")
    void csv_starts_with_bom() throws IOException {
        Map<String, Object> in = input("목록", "csv");
        in.put("sheets", List.of(Map.of("rows", List.of(List.of("구분", "건수"), List.of("파손", 40)))));

        ToolResult r = tool.execute(in, ctx);

        assertThat(r.success()).as(r.summary()).isTrue();
        byte[] bytes = Files.readAllBytes(out("목록.csv"));
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(body).contains("구분").contains("파손");
    }

    @Test
    @DisplayName("TC-CHT-081 md, html, txt 는 글 그대로 또는 HTML 쪽으로 쓴다")
    void text_formats_are_written() throws IOException {
        String md = "# 제목\n\n본문 글";
        assertThat(tool.execute(input("메모", "md", "content", md), ctx).success()).isTrue();
        assertThat(tool.execute(input("메모", "txt", "content", "그냥 글"), ctx).success()).isTrue();
        assertThat(tool.execute(input("메모", "html", "content", md), ctx).success()).isTrue();

        assertThat(Files.readString(out("메모.md"))).isEqualTo(md);
        assertThat(Files.readString(out("메모.txt"))).isEqualTo("그냥 글");
        String html = Files.readString(out("메모.html"));
        assertThat(html).contains("<h1>제목</h1>").contains("본문 글");
    }

    @Test
    @DisplayName("TC-CHT-082 경로 글자를 지운 이름으로 쓰고, 같은 이름은 (2), overwrite 참이면 바꿔 쓴다")
    void names_are_cleaned_and_collisions_numbered() throws IOException {
        ToolResult first = tool.execute(input("../a/b:c", "txt", "content", "하나"), ctx);
        ToolResult second = tool.execute(input("../a/b:c", "txt", "content", "둘"), ctx);
        Map<String, Object> replace = input("abc", "txt", "content", "셋");
        replace.put("overwrite", true);
        ToolResult third = tool.execute(replace, ctx);

        assertThat(fileName(first)).isEqualTo("abc.txt");
        assertThat(fileName(second)).isEqualTo("abc (2).txt");
        assertThat(fileName(third)).isEqualTo("abc.txt");
        assertThat(Files.readString(out("abc.txt"))).isEqualTo("셋");
        assertThat(Files.readString(out("abc (2).txt"))).isEqualTo("둘");
        assertThat(Files.exists(out("abc (3).txt"))).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-082 지우고 나니 빈 이름은 file_invalid:file_name")
    void empty_name_after_cleaning_is_rejected() {
        ToolResult r = tool.execute(input("/:*?", "txt", "content", "글"), ctx);

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).startsWith(CreateFileTool.ERR_FILE_NAME);
        assertThat(Files.exists(ws.resolve("out"))).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-082 크기 상한을 넘으면 file_too_large 이고 파일을 쓰지 않는다")
    void too_large_is_rejected_without_file() {
        when(settings.getLong(eq(CreateFileTool.MAX_BYTES_KEY), anyLong())).thenReturn(10L);

        ToolResult r = tool.execute(input("큰글", "txt", "content", "열 바이트를 넘는 글입니다"), ctx);

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).startsWith(CreateFileTool.ERR_TOO_LARGE);
        assertThat(Files.exists(out("큰글.txt"))).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-082 입력 JSON 은 out/.source 에 남고 산출물 목록에는 .source 가 안 보인다")
    void source_json_is_saved_and_hidden_from_list() throws IOException {
        Map<String, Object> in = input("../a/b:c", "txt", "content", "하나");
        ToolResult r = tool.execute(in, ctx);
        assertThat(r.success()).as(r.summary()).isTrue();

        Path source = ws.resolve("out").resolve(CreateFileTool.SOURCE_DIR).resolve("abc.txt.json");
        assertThat(source).exists();
        Map<?, ?> saved = new ObjectMapper().readValue(source.toFile(), Map.class);
        assertThat(saved.get("file_name")).isEqualTo("../a/b:c");
        assertThat(saved.get("content")).isEqualTo("하나");
        try (Stream<Path> files = Files.list(ws.resolve("out"))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }

        SessionStore sessionStore = mock(SessionStore.class);
        when(sessionStore.getWorkspaceRef("s1")).thenReturn(ws.toString());
        List<ArtifactService.ArtifactInfo> listed = new ArtifactService(sessionStore, props).listArtifacts("s1");
        assertThat(listed).extracting(ArtifactService.ArtifactInfo::name).containsExactly("abc.txt");
    }

    @Test
    @DisplayName("TC-CHT-082 실행 문맥에 작업 폴더가 없으면 세션 작업 폴더를 잡고, 허용 폴더 밖이면 쓰지 않는다")
    void workspace_falls_back_to_session_and_respects_whitelist() throws IOException {
        when(assigner.assign("s1")).thenReturn(ws.toString());
        ToolResult assigned = tool.execute(input("메모", "txt", "content", "글"), context(null));
        assertThat(assigned.success()).as(assigned.summary()).isTrue();
        assertThat(out("메모.txt")).exists();

        Path outside = Files.createTempDirectory("cr182-outside");
        try {
            ToolResult rejected = tool.execute(input("메모", "txt", "content", "글"), context(outside.toString()));
            assertThat(rejected.success()).isFalse();
            assertThat(rejected.summary()).isEqualTo(CreateFileTool.ERR_NO_WORKSPACE);
            assertThat(outside.resolve("out")).doesNotExist();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("TC-CHT-083 설정이 꺼져 있으면 불러도 file_create_disabled")
    void disabled_setting_rejects_call() {
        when(settings.getString(eq(FileCreateToolGate.ENABLED_KEY), any())).thenReturn("false");

        ToolResult r = tool.execute(input("보고서", "txt", "content", "글"), ctx);

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).isEqualTo(CreateFileTool.ERR_DISABLED);
        assertThat(Files.exists(ws.resolve("out"))).isFalse();
    }

    private Path out(String name) {
        return ws.resolve("out").resolve(name);
    }

    private static ToolContext context(String workspacePath) {
        return new ToolContext("t1", null, null, "s1", null, null, "user-a",
                PermissionLevel.RESTRICTED_WRITE, ApprovalState.NOT_REQUIRED, workspacePath, false, 1);
    }

    private static Map<String, Object> input(String fileName, String format, Object... pairs) {
        Map<String, Object> in = new HashMap<>();
        in.put("file_name", fileName);
        in.put("format", format);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            in.put((String) pairs[i], pairs[i + 1]);
        }
        return in;
    }

    private static String fileName(ToolResult r) {
        assertThat(r.success()).as(r.summary()).isTrue();
        return (String) ((Map<?, ?>) r.output()).get("file_name");
    }

    private static String shapesText(List<XSLFShape> shapes) {
        StringBuilder sb = new StringBuilder();
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape text) sb.append(text.getText()).append('\n');
        }
        return sb.toString();
    }
}
