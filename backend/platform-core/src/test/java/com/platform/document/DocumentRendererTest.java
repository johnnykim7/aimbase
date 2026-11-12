package com.platform.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** CR-182 TC-CHT-080: 마크다운 그리기(HTML, DOCX, PDF). */
class DocumentRendererTest {

    private static final String MARKDOWN = String.join("\n",
            "## 요약",
            "",
            "- 상위 항목",
            "  - 하위 항목",
            "- 둘째 항목",
            "",
            "| 구분 | 건수 |",
            "|---|---|",
            "| 파손 | 40 |",
            "| 단순 변심 | 80 |",
            "",
            "```",
            "code block",
            "```",
            "",
            "<script>alert(1)</script>",
            "",
            "[누르기](javascript:alert(1)) 와 [안전](https://example.com/a)",
            "",
            "![차트](https://example.com/chart.png)",
            "");

    @TempDir Path emptyFontDir;

    @Test
    @DisplayName("TC-CHT-080 HTML 은 script 를 글자로, 위험 링크는 빼고, 외부 이미지는 링크로 그린다")
    void html_escapes_script_drops_unsafe_links_and_links_external_images() {
        String html = new DocumentRenderer(new DocumentFonts(null)).html(DocModel.single("보고서", MARKDOWN));

        assertThat(html).doesNotContain("<script");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("javascript:");
        assertThat(html).contains("href=\"https://example.com/a\">안전</a>");
        assertThat(html).contains("<a href=\"https://example.com/chart.png\">차트</a>");
        assertThat(html).doesNotContain("<img");
        assertThat(html).contains("<h1 class=\"doc-title\">보고서</h1>");
    }

    @Test
    @DisplayName("TC-CHT-080 DOCX 를 POI 로 다시 읽으면 제목 글꼴, 표 행 수, 목록 단계가 맞다")
    void docx_keeps_heading_table_rows_and_list_depth() throws IOException {
        byte[] bytes = new DocumentRenderer(new DocumentFonts(null)).docx(DocModel.single("보고서", MARKDOWN));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFParagraph title = paragraph(doc, "보고서");
            assertThat(title.getRuns()).anyMatch(XWPFRun::isBold);

            XWPFParagraph heading = paragraph(doc, "요약");
            assertThat(heading.getRuns()).allMatch(XWPFRun::isBold);
            assertThat(heading.getRuns().get(0).getFontSizeAsDouble()).isEqualTo(15.0);

            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getTables().get(0).getRows()).hasSize(3);

            XWPFParagraph top = paragraph(doc, "• 상위 항목");
            XWPFParagraph nested = paragraph(doc, "• 하위 항목");
            XWPFParagraph second = paragraph(doc, "• 둘째 항목");
            assertThat(top.getIndentationLeft()).isPositive();
            assertThat(second.getIndentationLeft()).isEqualTo(top.getIndentationLeft());
            assertThat(nested.getIndentationLeft()).isEqualTo(top.getIndentationLeft() * 2);
        }
    }

    @Test
    @DisplayName("TC-CHT-080 PDF 를 PDFBox 로 읽으면 한글 글이 나온다")
    void pdf_text_contains_korean() throws IOException {
        DocumentFonts fonts = TestFonts.find();
        assumeTrue(fonts.available(), "한글 TTF 폴더가 없어 PDF 확인을 건너뛴다");

        byte[] bytes = new DocumentRenderer(fonts).pdf(DocModel.single("보고서", MARKDOWN));

        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("요약");
            assertThat(text).contains("상위 항목");
            assertThat(text).contains("파손");
        }
    }

    @Test
    @DisplayName("TC-CHT-080 글꼴이 없으면 PDF 는 pdf_font_missing 으로 거절한다")
    void pdf_without_fonts_is_rejected() {
        DocumentRenderer renderer = new DocumentRenderer(new DocumentFonts(emptyFontDir.toString()));

        assertThat(renderer.pdfAvailable()).isFalse();
        assertThatThrownBy(() -> renderer.pdf(DocModel.single("보고서", MARKDOWN)))
                .isInstanceOf(DocumentRenderException.class)
                .extracting(e -> ((DocumentRenderException) e).getCode())
                .isEqualTo(DocumentRenderException.PDF_FONT_MISSING);
    }

    private static XWPFParagraph paragraph(XWPFDocument doc, String text) {
        return doc.getParagraphs().stream()
                .filter(p -> text.equals(p.getText().strip()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("문단 없음: " + text));
    }
}
