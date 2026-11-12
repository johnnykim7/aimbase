package com.platform.document;

import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 마크다운 해석 결과를 워드 문서로 옮긴다(CR-182 3.1 의 4).
 *
 * <p>워드 기본 서식(제목 스타일, 번호 매기기 정의)에 기대지 않고 글자 서식을 바로 준다. 빈 문서에는 그
 * 정의가 없어서, 기대면 받는 사람의 워드 판에 따라 모양이 달라진다. 글머리와 번호는 글자로 붙이고
 * 들여쓰기로 겹침을 나타낸다.
 */
final class MarkdownDocxWriter {

    private static final String MONO_FONT = "Consolas";
    private static final String GREY = "6B6B6B";
    private static final String QUOTE_GREY = "4A4A4A";
    private static final String CODE_FILL = "F2F2F2";
    private static final String LINK_BLUE = "1F4E99";
    private static final int INDENT_STEP = 360;
    private static final int[] HEADING_SIZES = {18, 15, 13, 12, 11, 11};

    private final DocumentRenderer renderer;

    MarkdownDocxWriter(DocumentRenderer renderer) {
        this.renderer = renderer;
    }

    byte[] write(DocModel model) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (notBlank(model.notice())) {
                XWPFParagraph p = doc.createParagraph();
                p.setBorderTop(Borders.SINGLE);
                p.setBorderBottom(Borders.SINGLE);
                p.setBorderLeft(Borders.SINGLE);
                p.setBorderRight(Borders.SINGLE);
                XWPFRun r = p.createRun();
                r.setText(clean(model.notice()));
                r.setFontSize(9);
                r.setColor(QUOTE_GREY);
            }
            if (notBlank(model.title())) {
                XWPFRun r = doc.createParagraph().createRun();
                r.setText(clean(model.title()));
                r.setBold(true);
                r.setFontSize(20);
            }
            for (String line : model.meta()) {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingAfter(0);
                XWPFRun r = p.createRun();
                r.setText(clean(line));
                r.setFontSize(9);
                r.setColor(GREY);
            }
            for (DocModel.Section s : model.sections()) {
                if (notBlank(s.heading())) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setBorderBottom(Borders.SINGLE);
                    p.setSpacingBefore(240);
                    XWPFRun r = p.createRun();
                    r.setText(clean(s.heading()));
                    r.setBold(true);
                    r.setFontSize(10);
                    r.setColor(GREY);
                }
                blocks(doc, renderer.parse(s.markdown()), new Block(0, false));
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 들여쓰기 칸 수와 인용 안인지. */
    private record Block(int indent, boolean quote) {
        Block deeper() {
            return new Block(indent + 1, quote);
        }

        Block quoted() {
            return new Block(indent + 1, true);
        }
    }

    private void blocks(XWPFDocument doc, Node parent, Block ctx) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            block(doc, n, ctx, null);
        }
    }

    /** marker 는 목록 항목의 첫 문단 앞에 붙일 글머리. 첫 문단을 쓰고 나면 null 로 넘긴다. */
    private String block(XWPFDocument doc, Node n, Block ctx, String marker) {
        if (n instanceof Heading h) {
            XWPFParagraph p = paragraph(doc, ctx);
            p.setSpacingBefore(200);
            int size = HEADING_SIZES[Math.min(Math.max(h.getLevel(), 1), 6) - 1];
            inlines(p, h, new Style(true, false, false, false, null, size, ctx.quote()));
            return marker;
        }
        if (n instanceof Paragraph para) {
            XWPFParagraph p = paragraph(doc, ctx);
            if (marker != null) {
                p.setIndentationHanging(INDENT_STEP);
                XWPFRun r = p.createRun();
                r.setText(marker);
                if (ctx.quote()) r.setColor(QUOTE_GREY);
            }
            inlines(p, para, Style.plain(ctx.quote()));
            return null;
        }
        if (n instanceof BulletList list) {
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                listItem(doc, item, ctx.deeper(), "• ");
            }
            return marker;
        }
        if (n instanceof OrderedList list) {
            Integer start = list.getMarkerStartNumber();
            int num = start == null ? 1 : start;
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                listItem(doc, item, ctx.deeper(), (num++) + ". ");
            }
            return marker;
        }
        if (n instanceof FencedCodeBlock code) {
            codeBlock(doc, code.getLiteral(), ctx);
            return marker;
        }
        if (n instanceof IndentedCodeBlock code) {
            codeBlock(doc, code.getLiteral(), ctx);
            return marker;
        }
        if (n instanceof HtmlBlock html) {
            XWPFParagraph p = paragraph(doc, ctx);
            XWPFRun r = p.createRun();
            r.setText(clean(html.getLiteral()));
            return marker;
        }
        if (n instanceof BlockQuote quote) {
            blocks(doc, quote, ctx.quoted());
            return marker;
        }
        if (n instanceof ThematicBreak) {
            paragraph(doc, ctx).setBorderBottom(Borders.SINGLE);
            return marker;
        }
        if (n instanceof TableBlock table) {
            table(doc, table);
            return marker;
        }
        blocks(doc, n, ctx);
        return marker;
    }

    private void listItem(XWPFDocument doc, Node item, Block ctx, String marker) {
        if (!(item instanceof ListItem)) return;
        String pending = marker;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (pending != null && !(child instanceof Paragraph)) {
                XWPFParagraph p = paragraph(doc, ctx);
                p.setIndentationHanging(INDENT_STEP);
                p.createRun().setText(pending);
                pending = null;
            }
            pending = block(doc, child, ctx, pending);
        }
        if (pending != null) {
            XWPFParagraph p = paragraph(doc, ctx);
            p.createRun().setText(pending);
        }
    }

    private void codeBlock(XWPFDocument doc, String literal, Block ctx) {
        XWPFParagraph p = paragraph(doc, ctx);
        shade(p);
        String text = literal == null ? "" : clean(literal);
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            XWPFRun r = p.createRun();
            r.setFontFamily(MONO_FONT);
            r.setFontSize(9);
            r.setText(lines[i]);
            if (i < lines.length - 1) r.addBreak();
        }
    }

    private void table(XWPFDocument doc, TableBlock block) {
        List<List<TableCell>> rows = new ArrayList<>();
        int cols = 0;
        for (Node section = block.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                List<TableCell> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell tc) cells.add(tc);
                }
                cols = Math.max(cols, cells.size());
                rows.add(cells);
            }
        }
        if (rows.isEmpty() || cols == 0) return;
        XWPFTable table = doc.createTable(rows.size(), cols);
        table.setWidth("100%");
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = table.getRow(i);
            List<TableCell> cells = rows.get(i);
            for (int c = 0; c < cols; c++) {
                XWPFTableCell cell = row.getCell(c);
                XWPFParagraph p = cell.getParagraphs().get(0);
                if (c >= cells.size()) continue;
                TableCell src = cells.get(c);
                if (src.isHeader()) cell.setColor(CODE_FILL);
                inlines(p, src, new Style(src.isHeader(), false, false, false, null, 0, false));
            }
        }
        doc.createParagraph();
    }

    private XWPFParagraph paragraph(XWPFDocument doc, Block ctx) {
        XWPFParagraph p = doc.createParagraph();
        if (ctx.indent() > 0) p.setIndentationLeft(INDENT_STEP * ctx.indent());
        if (ctx.quote()) p.setBorderLeft(Borders.SINGLE);
        return p;
    }

    private static void shade(XWPFParagraph p) {
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTShd shd = ppr.isSetShd() ? ppr.getShd() : ppr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(CODE_FILL);
    }

    /** 글자 서식. size 0 은 문서 기본 크기. */
    private record Style(boolean bold, boolean italic, boolean code, boolean strike, String link, int size,
                         boolean quote) {
        static Style plain(boolean quote) {
            return new Style(false, false, false, false, null, 0, quote);
        }

        Style withBold() {
            return new Style(true, italic, code, strike, link, size, quote);
        }

        Style withItalic() {
            return new Style(bold, true, code, strike, link, size, quote);
        }

        Style withCode() {
            return new Style(bold, italic, true, strike, link, size, quote);
        }

        Style withStrike() {
            return new Style(bold, italic, code, true, link, size, quote);
        }

        Style withLink(String url) {
            return new Style(bold, italic, code, strike, url, size, quote);
        }
    }

    private void inlines(XWPFParagraph p, Node parent, Style style) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            inline(p, n, style);
        }
    }

    private void inline(XWPFParagraph p, Node n, Style style) {
        if (n instanceof Text t) {
            run(p, t.getLiteral(), style);
        } else if (n instanceof Code c) {
            run(p, c.getLiteral(), style.withCode());
        } else if (n instanceof HtmlInline h) {
            run(p, h.getLiteral(), style);
        } else if (n instanceof SoftLineBreak) {
            run(p, " ", style);
        } else if (n instanceof HardLineBreak) {
            p.createRun().addBreak();
        } else if (n instanceof StrongEmphasis) {
            inlines(p, n, style.withBold());
        } else if (n instanceof Emphasis) {
            inlines(p, n, style.withItalic());
        } else if (n instanceof Strikethrough) {
            inlines(p, n, style.withStrike());
        } else if (n instanceof Link link) {
            String url = MarkdownText.isSafeLink(link.getDestination()) ? link.getDestination().strip() : null;
            inlines(p, n, style.withLink(url));
        } else if (n instanceof Image image) {
            String alt = MarkdownText.plain(image);
            String url = MarkdownText.isSafeLink(image.getDestination()) ? image.getDestination().strip() : null;
            run(p, alt.isBlank() ? image.getDestination() : alt, style.withLink(url));
        } else {
            inlines(p, n, style);
        }
    }

    private static void run(XWPFParagraph p, String text, Style style) {
        if (text == null || text.isEmpty()) return;
        XWPFRun r = style.link() != null ? p.createHyperlinkRun(style.link()) : p.createRun();
        r.setText(clean(text));
        if (style.bold()) r.setBold(true);
        if (style.italic()) r.setItalic(true);
        if (style.strike()) r.setStrikeThrough(true);
        if (style.size() > 0) r.setFontSize(style.size());
        if (style.code()) {
            r.setFontFamily(MONO_FONT);
            if (style.size() == 0) r.setFontSize(9);
        }
        if (style.link() != null) {
            r.setUnderline(UnderlinePatterns.SINGLE);
            r.setColor(LINK_BLUE);
        } else if (style.quote()) {
            r.setColor(QUOTE_GREY);
        }
    }

    private static String clean(String s) {
        return s == null ? "" : DocumentRenderer.xmlSafe(s);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
