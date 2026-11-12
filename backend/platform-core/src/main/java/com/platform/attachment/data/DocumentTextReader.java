package com.platform.attachment.data;

import com.platform.attachment.AttachmentException;
import com.platform.attachment.AttachmentKind;
import com.platform.attachment.TextFileDecoder;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * CR-183: 워드, 텍스트 첨부의 본문을 읽는다(BIZ-221). 워드는 문단 순서대로 읽고 표는 마크다운 표로 바꾼다.
 */
public final class DocumentTextReader {

    private DocumentTextReader() {}

    public static String read(byte[] bytes, AttachmentKind kind) {
        return switch (kind) {
            case DOCX -> readDocx(bytes);
            case TEXT, MARKDOWN -> {
                String text = TextFileDecoder.decode(bytes);
                if (text == null) {
                    throw new AttachmentException(HttpStatus.UNPROCESSABLE_ENTITY,
                            AttachmentException.CODE_MIME_MISMATCH, "file is not UTF-8 or CP949 text");
                }
                yield text;
            }
            default -> throw new IllegalArgumentException("not a text file: " + kind);
        };
    }

    private static String readDocx(byte[] bytes) {
        OfficeFiles.assertOoxml(bytes, "word/document.xml");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    out.append(p.getText()).append('\n');
                } else if (element instanceof XWPFTable t) {
                    appendTable(t, out);
                } else if (element instanceof XWPFSDT sdt) {
                    out.append(sdt.getContent().getText()).append('\n');
                }
            }
            return out.toString().strip();
        } catch (AttachmentException e) {
            throw e;
        } catch (Exception e) {
            throw OfficeFiles.unreadable(e.getClass().getSimpleName());
        }
    }

    private static void appendTable(XWPFTable table, StringBuilder out) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;
        int width = 0;
        for (XWPFTableRow row : rows) width = Math.max(width, row.getTableCells().size());
        if (width == 0) return;
        out.append('\n');
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : rows.get(r).getTableCells()) cells.add(markdownCell(cell.getText()));
            while (cells.size() < width) cells.add("");
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (r == 0) out.append("|").append(" --- |".repeat(width)).append('\n');
        }
        out.append('\n');
    }

    static String markdownCell(String text) {
        if (text == null) return "";
        return text.replace("\r", " ").replace("\n", " ").replace("|", "\\|").strip();
    }
}
