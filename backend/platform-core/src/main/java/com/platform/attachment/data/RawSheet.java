package com.platform.attachment.data;

import com.platform.attachment.AttachmentException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * CR-183: 형을 정하기 전의 시트 하나. 칸 글을 열 자리대로 모은다.
 *
 * <p>모든 칸이 빈 행은 넣지 않는다. 행과 열이 상한을 넘는 순간 읽기를 멈추고 거절한다(BIZ-221).
 */
final class RawSheet {

    private final String sheetName;
    private final int maxRows;
    private final int maxColumns;
    private final List<List<String>> rows = new ArrayList<>();
    private List<String> current;
    private int width;

    RawSheet(String sheetName, int maxRows, int maxColumns) {
        this.sheetName = sheetName;
        this.maxRows = maxRows;
        this.maxColumns = maxColumns;
    }

    void startRow() {
        current = new ArrayList<>();
    }

    void cell(int column, String value) {
        if (current == null) startRow();
        if (value == null) return;
        String trimmed = value.strip();
        if (trimmed.isEmpty()) return;
        if (column >= maxColumns) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST, AttachmentException.CODE_COLUMNS_EXCEEDED,
                    "table has more than " + maxColumns + " columns", (long) maxColumns);
        }
        while (current.size() <= column) current.add(null);
        current.set(column, trimmed);
        width = Math.max(width, column + 1);
    }

    void endRow() {
        if (current != null && !current.isEmpty()) {
            rows.add(current);
            // 첫 행은 머리 행이다
            if (rows.size() - 1 > maxRows) {
                throw new AttachmentException(HttpStatus.BAD_REQUEST, AttachmentException.CODE_ROWS_EXCEEDED,
                        "table has more than " + maxRows + " rows", (long) maxRows);
            }
        }
        current = null;
    }

    String sheetName() { return sheetName; }

    List<List<String>> rows() { return rows; }

    int width() { return width; }

    boolean isEmpty() { return rows.isEmpty(); }
}
