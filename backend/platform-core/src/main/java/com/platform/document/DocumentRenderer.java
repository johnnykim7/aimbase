package com.platform.document;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 마크다운을 HTML, PDF, DOCX 로 그리는 공통 부품(CR-182 3.1). 파일 만들기, 응답 저장, 대화 내보내기,
 * 공유 사본이 모두 이것을 쓴다.
 *
 * <p>마크다운 안 HTML 은 글자로 바꾸고(escapeHtml) 위험한 링크는 지운다(sanitizeUrls). 공유 화면은
 * 링크를 가진 누구나 열기 때문에, 모델이나 사용자가 쓴 글이 화면 코드로 돌면 안 된다(BIZ-213).
 * 그림은 받아 오지 않고 링크로 남긴다. PDF 를 그리는 동안 서버가 바깥으로 요청을 보내지 않게 하려는 것이다.
 */
@Component
public class DocumentRenderer {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create());

    private static final String FALLBACK_SANS =
            "-apple-system, 'Apple SD Gothic Neo', 'Malgun Gothic', 'Noto Sans KR', sans-serif";
    private static final String FALLBACK_MONO = "Menlo, Consolas, monospace";

    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .nodeRendererFactory(LinkedImageRenderer::new)
            .build();

    private final DocumentFonts fonts;

    public DocumentRenderer(DocumentFonts fonts) {
        this.fonts = fonts;
    }

    public boolean pdfAvailable() {
        return fonts.available();
    }

    public Node parse(String markdown) {
        return parser.parse(xmlSafe(markdown == null ? "" : markdown));
    }

    /** 스타일을 품은 한 장짜리 HTML. 스크립트와 바깥 파일이 없다. */
    public String html(DocModel doc) {
        StringBuilder body = new StringBuilder();
        if (doc.notice() != null && !doc.notice().isBlank()) {
            body.append("<p class=\"notice\">").append(escape(doc.notice())).append("</p>\n");
        }
        if (doc.title() != null && !doc.title().isBlank()) {
            body.append("<h1 class=\"doc-title\">").append(escape(doc.title())).append("</h1>\n");
        }
        for (String line : doc.meta()) {
            body.append("<p class=\"meta\">").append(escape(line)).append("</p>\n");
        }
        for (DocModel.Section s : doc.sections()) {
            body.append("<section>\n");
            if (s.heading() != null && !s.heading().isBlank()) {
                body.append("<h2 class=\"section-head\">").append(escape(s.heading())).append("</h2>\n");
            }
            body.append(htmlRenderer.render(parse(s.markdown())));
            body.append("</section>\n");
        }
        String title = doc.title() != null && !doc.title().isBlank() ? doc.title() : "";
        return "<!DOCTYPE html>\n<html lang=\"ko\">\n<head>\n<meta charset=\"utf-8\" />\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
                + "<title>" + escape(title) + "</title>\n<style>\n" + css() + "</style>\n</head>\n<body>\n"
                + body + "</body>\n</html>\n";
    }

    public byte[] pdf(DocModel doc) {
        return pdfFromHtml(html(doc));
    }

    public byte[] pdfFromHtml(String html) {
        List<DocumentFonts.Face> faces = fonts.faces();
        if (faces.isEmpty()) {
            throw new DocumentRenderException(DocumentRenderException.PDF_FONT_MISSING);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            for (DocumentFonts.Face face : faces) {
                builder.useFont(face.file().toFile(), face.family(), face.weight(),
                        BaseRendererBuilder.FontStyle.NORMAL, true);
            }
            builder.useExternalResourceAccessControl((uri, type) -> false,
                    ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.useExternalResourceAccessControl((uri, type) -> false,
                    ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] docx(DocModel doc) {
        return new MarkdownDocxWriter(this).write(doc);
    }

    /** 마크다운 파일. 칸 머리는 2단 제목으로 적는다. */
    public String markdown(DocModel doc) {
        StringBuilder sb = new StringBuilder();
        if (doc.notice() != null && !doc.notice().isBlank()) sb.append("> ").append(doc.notice()).append("\n\n");
        if (doc.title() != null && !doc.title().isBlank()) sb.append("# ").append(doc.title()).append("\n\n");
        for (String line : doc.meta()) sb.append(line).append("  \n");
        if (!doc.meta().isEmpty()) sb.append('\n');
        for (DocModel.Section s : doc.sections()) {
            if (s.heading() != null && !s.heading().isBlank()) sb.append("## ").append(s.heading()).append("\n\n");
            String md = s.markdown() == null ? "" : s.markdown().strip();
            sb.append(md).append("\n\n");
        }
        return sb.toString();
    }

    private String css() {
        List<String> sans = new ArrayList<>();
        for (String f : fonts.sansFamilies()) sans.add(quote(f));
        List<String> mono = new ArrayList<>();
        for (String f : fonts.monoFamilies()) mono.add(quote(f));
        String sansStack = join(sans, FALLBACK_SANS);
        String monoStack = join(mono, join(sans, FALLBACK_MONO));
        return "@page { size: A4; margin: 18mm 16mm; }\n"
                + "body { font-family: " + sansStack + "; font-size: 10.5pt; line-height: 1.6; color: #1f1f1f;"
                + " background: #ffffff; margin: 0 auto; max-width: 820px; padding: 0 16px; word-wrap: break-word; }\n"
                + "h1, h2, h3, h4, h5, h6 { line-height: 1.3; margin: 1.2em 0 0.5em; }\n"
                + "h1 { font-size: 18pt; } h2 { font-size: 14pt; } h3 { font-size: 12pt; } h4, h5, h6 { font-size: 11pt; }\n"
                + "h1.doc-title { margin-top: 0.4em; }\n"
                + "p.meta { color: #6b6b6b; font-size: 9pt; margin: 0.1em 0; }\n"
                + "p.notice { color: #3d3d3d; font-size: 9pt; border: 1px solid #d0d0d0; padding: 6px 10px; }\n"
                + "section { margin-top: 1.2em; }\n"
                + "h2.section-head { font-size: 10pt; color: #6b6b6b; border-bottom: 1px solid #e0e0e0;"
                + " padding-bottom: 3px; }\n"
                + "table { border-collapse: collapse; width: 100%; margin: 0.8em 0; }\n"
                + "th, td { border: 1px solid #c8c8c8; padding: 4px 6px; text-align: left; vertical-align: top;"
                + " word-wrap: break-word; }\n"
                + "th { background: #f2f2f2; font-weight: bold; }\n"
                + "code { font-family: " + monoStack + "; background: #f2f2f2; padding: 0 3px; font-size: 9.5pt; }\n"
                + "pre { background: #f2f2f2; padding: 8px 10px; white-space: pre-wrap; word-wrap: break-word; }\n"
                + "pre code { background: none; padding: 0; }\n"
                + "blockquote { border-left: 3px solid #c8c8c8; margin: 0.8em 0; padding: 0 0 0 12px; color: #4a4a4a; }\n"
                + "hr { border: none; border-top: 1px solid #d0d0d0; margin: 1.2em 0; }\n"
                + "a { color: #1f1f1f; text-decoration: underline; }\n";
    }

    private static String join(List<String> head, String tail) {
        return head.isEmpty() ? tail : String.join(", ", head) + ", " + tail;
    }

    private static String quote(String family) {
        return "'" + family.replace("'", "") + "'";
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : xmlSafe(s).toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** XML 1.0 에 쓸 수 없는 제어 문자를 지운다. PDF 는 HTML 을 XML 로 읽어서 하나만 섞여도 통째로 실패한다. */
    static String xmlSafe(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF);
            if (!ok) {
                if (sb == null) sb = new StringBuilder(s.length()).append(s, 0, i);
                continue;
            }
            if (sb != null) sb.append(c);
        }
        return sb == null ? s : sb.toString();
    }

    /** 그림은 받아 오지 않고 대체 글을 링크로 남긴다. */
    static final class LinkedImageRenderer implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;

        LinkedImageRenderer(HtmlNodeRendererContext context) {
            this.context = context;
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Image.class);
        }

        @Override
        public void render(Node node) {
            Image image = (Image) node;
            String alt = MarkdownText.plain(image);
            String label = alt.isBlank() ? image.getDestination() : alt;
            String url = context.encodeUrl(image.getDestination());
            if (MarkdownText.isSafeLink(image.getDestination())) {
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("href", url);
                html.tag("a", context.extendAttributes(node, "a", attrs));
                html.text(label);
                html.tag("/a");
            } else {
                html.text(label);
            }
        }
    }
}
