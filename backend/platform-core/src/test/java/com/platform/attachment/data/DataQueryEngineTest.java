package com.platform.attachment.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-183 TC-CHT-094(계산), TC-CHT-095(막기), TC-CHT-096(상한). */
class DataQueryEngineTest {

    private static final int MAX_ROWS = 500;
    private static final int MAX_BYTES = 65_536;

    private static DataTable sales() {
        List<Object[]> rows = List.of(
                new Object[]{"서울", "강남", new BigDecimal("100"), new BigDecimal("0.1")},
                new Object[]{"서울", "종로", new BigDecimal("300"), new BigDecimal("0.2")},
                new Object[]{"부산", "해운대", new BigDecimal("200"), null});
        return new DataTable("t1", "s", List.of(
                new DataTable.Column("지역", DataTable.ColumnType.TEXT),
                new DataTable.Column("지점", DataTable.ColumnType.TEXT),
                new DataTable.Column("매출액", DataTable.ColumnType.NUMBER),
                new DataTable.Column("비율", DataTable.ColumnType.NUMBER)), rows);
    }

    private static DataQueryEngine.Result run(DataTable table, String sql) throws Exception {
        return DataQueryEngine.run(List.of(table), DataQueryEngine.normalize(sql), MAX_ROWS, MAX_BYTES, 10);
    }

    @Test
    @DisplayName("SUM, GROUP BY, 한글 열 이름")
    void sumGroupBy() throws Exception {
        DataQueryEngine.Result r = run(sales(),
                "SELECT \"지역\", SUM(\"매출액\") AS \"합계\" FROM \"t1\" GROUP BY \"지역\" ORDER BY \"합계\" DESC;");

        assertThat(r.columns()).extracting(c -> c.get("name")).containsExactly("지역", "합계");
        assertThat(r.rows()).containsExactly(
                List.of("서울", new BigDecimal("400")),
                List.of("부산", new BigDecimal("200")));
        assertThat(r.truncated()).isFalse();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r.toMap()))
                .contains("[\"서울\",400]");
    }

    @Test
    @DisplayName("창 함수 순위")
    void windowFunction() throws Exception {
        DataQueryEngine.Result r = run(sales(),
                "SELECT \"지점\", RANK() OVER (ORDER BY \"매출액\" DESC) AS \"순위\" FROM \"t1\" ORDER BY 2");

        assertThat(r.rows()).extracting(row -> row.get(0)).containsExactly("종로", "해운대", "강남");
        assertThat(r.rows().get(0).get(1)).isEqualTo(1L);
    }

    @Test
    @DisplayName("0.1 + 0.2 는 0.3")
    void decimalIsExact() throws Exception {
        DataQueryEngine.Result r = run(sales(), "SELECT SUM(\"비율\") FROM \"t1\"");

        assertThat(r.rows().get(0).get(0)).isEqualTo(new BigDecimal("0.3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO \"t1\" VALUES ('a', 'b', 1, 1)",
            "DROP TABLE \"t1\"",
            "SELECT 1; SELECT 2",
            "CALL FILE_READ('/etc/passwd')",
            "CREATE ALIAS X FOR \"java.lang.System.getProperty\"",
            "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT * FROM r",
            "SELECT REPEAT('a', 100000000)",
            "SELECT * FROM SYSTEM_RANGE(1, 100000000)"})
    @DisplayName("SELECT, WITH 한 문장이 아니거나 막은 함수면 문장 검사에서 거절한다")
    void rejectedByStatementCheck(String sql) {
        assertThatThrownBy(() -> DataQueryEngine.normalize(sql)).isInstanceOf(DataQueryEngine.QueryRejected.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT FILE_READ('/etc/passwd')",
            "SELECT * FROM CSVREAD('/etc/passwd')",
            "SELECT * FROM LINK_SCHEMA('X', '', 'jdbc:h2:mem:other', 'sa', '', 'PUBLIC')"})
    @DisplayName("관리 권한이 필요한 함수는 조회 계정에서 실패하고 파일을 읽지 않는다")
    void adminFunctionsFailForReader(String sql) {
        assertThatThrownBy(() -> run(sales(), sql)).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("큰 교차 조인은 시간 상한에 걸린다")
    void timeout() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 400; i++) rows.add(new Object[]{new BigDecimal(i)});
        DataTable big = new DataTable("t1", null,
                List.of(new DataTable.Column("n", DataTable.ColumnType.NUMBER)), rows);

        assertThatThrownBy(() -> DataQueryEngine.run(List.of(big),
                DataQueryEngine.normalize("SELECT SUM(a.\"n\" * b.\"n\" + c.\"n\" * d.\"n\") "
                        + "FROM \"t1\" a, \"t1\" b, \"t1\" c, \"t1\" d"), MAX_ROWS, MAX_BYTES, 1))
                .isInstanceOf(SQLTimeoutException.class);
    }

    @Test
    @DisplayName("결과 501행은 500행과 truncated, 300자 칸은 200자")
    void rowAndCellLimits() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) rows.add(new Object[]{new BigDecimal(i), "x".repeat(300)});
        DataTable t = new DataTable("t1", null, List.of(
                new DataTable.Column("n", DataTable.ColumnType.NUMBER),
                new DataTable.Column("memo", DataTable.ColumnType.TEXT)), rows);

        DataQueryEngine.Result r = DataQueryEngine.run(List.of(t),
                DataQueryEngine.normalize("SELECT * FROM \"t1\" ORDER BY \"n\""), MAX_ROWS, 1_000_000, 10);

        assertThat(r.rows()).hasSize(500);
        assertThat(r.truncated()).isTrue();
        assertThat((String) r.rows().get(0).get(1)).hasSize(200);
    }

    @Test
    @DisplayName("결과 크기 상한을 넘으면 행을 줄이고 truncated")
    void byteLimit() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(new Object[]{"row-" + i});
        DataTable t = new DataTable("t1", null, List.of(new DataTable.Column("v", DataTable.ColumnType.TEXT)), rows);

        DataQueryEngine.Result r = DataQueryEngine.run(List.of(t),
                DataQueryEngine.normalize("SELECT * FROM \"t1\""), MAX_ROWS, 300, 10);

        assertThat(r.truncated()).isTrue();
        assertThat(r.rows().size()).isBetween(1, 99);
    }
}
