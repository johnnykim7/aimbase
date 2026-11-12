package com.platform.document;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 표 파일(엑셀, CSV)을 만든다(CR-182 3.2). */
public final class TableFileWriter {

    public record SheetData(String name, List<List<Object>> rows) {}

    /**
     * 숫자 칸으로 바꿀 글. 앞자리 0(우편번호, 상품 코드)과 16자리 이상(주문번호, 카드번호)은 글로 둔다.
     * 엑셀 숫자는 15자리까지만 정확해서 그보다 긴 수는 끝자리가 0 으로 바뀐다.
     */
    private static final Pattern NUMERIC = Pattern.compile("-?(0|[1-9]\\d{0,14})(\\.\\d{1,15})?");

    private static final int MAX_COLUMN_CHARS = 60;
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TableFileWriter() {}

    public static byte[] xlsx(List<SheetData> sheets) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            Set<String> used = new HashSet<>();
            int index = 0;
            for (SheetData data : sheets) {
                index++;
                Sheet sheet = wb.createSheet(sheetName(data.name(), index, used));
                List<List<Object>> rows = data.rows() == null ? List.of() : data.rows();
                int[] widths = new int[0];
                for (int r = 0; r < rows.size(); r++) {
                    List<Object> values = rows.get(r) == null ? List.of() : rows.get(r);
                    Row row = sheet.createRow(r);
                    if (values.size() > widths.length) widths = java.util.Arrays.copyOf(widths, values.size());
                    for (int c = 0; c < values.size(); c++) {
                        Cell cell = row.createCell(c);
                        String shown = put(cell, values.get(c));
                        if (r == 0) cell.setCellStyle(head);
                        widths[c] = Math.max(widths[c], displayWidth(shown));
                    }
                }
                if (!rows.isEmpty()) sheet.createFreezePane(0, 1);
                for (int c = 0; c < widths.length; c++) {
                    int chars = Math.min(MAX_COLUMN_CHARS, Math.max(widths[c], 4)) + 2;
                    sheet.setColumnWidth(c, chars * 256);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** UTF-8 BOM 을 붙인다. 엑셀이 BOM 없는 CSV 를 지역 문자셋으로 읽어 한글이 깨진다. */
    public static byte[] csv(List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<Object> row : rows) {
            if (row == null) {
                sb.append("\r\n");
                continue;
            }
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                sb.append(csvField(text(row.get(c))));
            }
            sb.append("\r\n");
        }
        return withBom(sb.toString());
    }

    public static byte[] withBom(String text) {
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    private static String put(Cell cell, Object value) {
        if (value == null) return "";
        if (value instanceof Boolean b) {
            cell.setCellValue(b);
            return b.toString();
        }
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            return n.toString();
        }
        String s = clip(DocumentRenderer.xmlSafe(text(value)));
        if (NUMERIC.matcher(s).matches()) {
            cell.setCellValue(new BigDecimal(s).doubleValue());
        } else {
            cell.setCellValue(s);
        }
        return s;
    }

    private static String clip(String s) {
        int max = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String sheetName(String name, int index, Set<String> used) {
        String base = name == null || name.isBlank() ? "Sheet" + index : WorkbookUtil.createSafeSheetName(name.strip());
        String candidate = base;
        int n = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            String suffix = " (" + n++ + ")";
            candidate = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        return candidate;
    }

    /** 한글, 한자 같은 넓은 글자는 두 칸으로 센다. 글꼴 측정(AWT)을 쓰지 않으려는 근사다. */
    private static int displayWidth(String s) {
        int longest = 0;
        for (String line : s.split("\n", -1)) {
            int w = 0;
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                w += isWide(cp) ? 2 : 1;
                i += Character.charCount(cp);
            }
            longest = Math.max(longest, w);
        }
        return longest;
    }

    private static boolean isWide(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                || (cp >= 0xFF01 && cp <= 0xFF60);
    }

    private static String csvField(String s) {
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof BigDecimal d) return d.toPlainString();
        return value.toString();
    }
}
