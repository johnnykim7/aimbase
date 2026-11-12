package com.platform.attachment.data;

/**
 * CR-183: CSV, TSV 를 칸으로 나눈다. 큰따옴표로 감싼 칸 안의 구분자, 줄바꿈, 두 겹 큰따옴표를 받는다(RFC 4180).
 * 엑셀이 저장한 TSV 도 탭이나 큰따옴표가 든 칸은 큰따옴표로 감싼다.
 */
final class DelimitedTextParser {

    private DelimitedTextParser() {}

    static void parse(String text, char delimiter, RawSheet sheet) {
        int length = text.length();
        int i = 0;
        int column = 0;
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean rowOpen = false;
        while (i < length) {
            char c = text.charAt(i);
            if (!rowOpen) {
                sheet.startRow();
                rowOpen = true;
                column = 0;
            }
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    cell.append(c);
                }
                i++;
                continue;
            }
            if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                sheet.cell(column++, cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                sheet.cell(column, cell.toString());
                cell.setLength(0);
                sheet.endRow();
                rowOpen = false;
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') i++;
            } else {
                cell.append(c);
            }
            i++;
        }
        if (rowOpen) {
            sheet.cell(column, cell.toString());
            sheet.endRow();
        }
    }
}
