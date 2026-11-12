package com.platform.attachment.data;

import java.util.List;

/**
 * CR-183: 데이터 파일에서 읽은 표 하나(BIZ-221).
 *
 * @param name   계산에 쓰는 표 이름. 비지 않은 시트 순서대로 t1, t2. CSV, TSV 는 t1
 * @param sheet  엑셀 시트 이름. CSV, TSV 는 null
 * @param rows   열 형에 맞춘 값(BigDecimal, LocalDate, LocalDateTime, String). 빈 칸은 null
 */
public record DataTable(String name, String sheet, List<Column> columns, List<Object[]> rows) {

    public record Column(String name, ColumnType type) {}

    public enum ColumnType {
        NUMBER("숫자", "DECFLOAT"),
        DATE("날짜", "DATE"),
        TIMESTAMP("날짜시각", "TIMESTAMP"),
        TEXT("글", "CHARACTER VARYING");

        private final String label;
        private final String sqlType;

        ColumnType(String label, String sqlType) {
            this.label = label;
            this.sqlType = sqlType;
        }

        public String label() { return label; }

        public String sqlType() { return sqlType; }
    }
}
