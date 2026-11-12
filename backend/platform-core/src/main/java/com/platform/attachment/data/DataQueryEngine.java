package com.platform.attachment.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CR-183: data_query 의 계산 엔진(T3-2, BIZ-222).
 *
 * <p>호출마다 메모리 DB(H2, PostgreSQL 호환 모드)를 새로 열어 관리 계정으로 표를 만들고, 관리 권한이 없는
 * 조회 계정에 SELECT 권한만 줘 그 계정으로 문장을 실행한다. 관리 권한이 없으면 파일 읽고 쓰기, 다른 DB 연결,
 * 함수 만들기, 스크립트 실행이 막힌다. 마지막 연결을 닫으면 DB 가 메모리에서 사라진다.
 *
 * <p>서버와 같은 JVM 에서 돌므로 큰 글이나 큰 행 집합을 만들어 메모리를 채우는 함수와 재귀 문장은 받지 않는다.
 */
public final class DataQueryEngine {

    static final int MAX_CELL_CHARS = 200;
    private static final Pattern STATEMENT_HEAD = Pattern.compile("(?is)^(SELECT|WITH)\\b.*");
    private static final Pattern BLOCKED = Pattern.compile(
            "(?i)\\bRECURSIVE\\b|\\b(REPEAT|SPACE|LPAD|RPAD|SYSTEM_RANGE|GENERATE_SERIES)\\s*\\(");
    private static final String ADMIN = "SA";
    private static final String READER = "READER";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DataQueryEngine() {}

    public record Result(List<Map<String, Object>> columns, List<List<Object>> rows, boolean truncated,
                         long elapsedMs) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", columns);
            out.put("rows", rows);
            out.put("truncated", truncated);
            out.put("elapsed_ms", elapsedMs);
            return out;
        }
    }

    /** 문장 검사에 걸리면 이유를 담는다. 모델이 고쳐 다시 부른다. */
    public static final class QueryRejected extends Exception {
        public QueryRejected(String message) { super(message); }
    }

    /**
     * 앞뒤 빈칸과 끝 세미콜론 하나를 뺀 문장. SELECT 또는 WITH 로 시작하는 한 문장만 받는다.
     */
    public static String normalize(String sql) throws QueryRejected {
        if (sql == null || sql.isBlank()) throw new QueryRejected("sql is required");
        String s = sql.strip();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).strip();
        if (s.contains(";")) throw new QueryRejected("only one statement is allowed");
        if (!STATEMENT_HEAD.matcher(s).matches()) {
            throw new QueryRejected("only a SELECT or WITH statement is allowed");
        }
        var blocked = BLOCKED.matcher(s);
        if (blocked.find()) {
            throw new QueryRejected("not allowed in data_query: " + blocked.group().replace("(", "").strip()
                    .toUpperCase(Locale.ROOT));
        }
        return s;
    }

    public static Result run(List<DataTable> tables, String statement, int maxRows, int maxBytes,
                             int timeoutSeconds) throws SQLException {
        String url = "jdbc:h2:mem:dq_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        String adminPassword = randomSecret();
        String readerPassword = randomSecret();
        try (Connection admin = connect(url, ADMIN, adminPassword)) {
            load(admin, tables, readerPassword);
            try (Connection reader = connect(url, READER, readerPassword)) {
                return query(reader, statement, maxRows, maxBytes, timeoutSeconds);
            }
        }
    }

    private static Connection connect(String url, String user, String password) throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds.getConnection();
    }

    private static void load(Connection admin, List<DataTable> tables, String readerPassword) throws SQLException {
        try (Statement st = admin.createStatement()) {
            for (DataTable table : tables) {
                StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(table.name())).append(" (");
                for (int c = 0; c < table.columns().size(); c++) {
                    DataTable.Column col = table.columns().get(c);
                    if (c > 0) ddl.append(", ");
                    ddl.append(quote(col.name())).append(' ').append(col.type().sqlType());
                }
                st.execute(ddl.append(')').toString());
                insertRows(admin, table);
            }
            st.execute("CREATE USER " + quote(READER) + " PASSWORD '" + readerPassword + "'");
            for (DataTable table : tables) {
                st.execute("GRANT SELECT ON " + quote(table.name()) + " TO " + quote(READER));
            }
        }
    }

    private static void insertRows(Connection admin, DataTable table) throws SQLException {
        int width = table.columns().size();
        if (width == 0 || table.rows().isEmpty()) return;
        String sql = "INSERT INTO " + quote(table.name()) + " VALUES (" + "?, ".repeat(width - 1) + "?)";
        try (PreparedStatement ps = admin.prepareStatement(sql)) {
            int batch = 0;
            for (Object[] row : table.rows()) {
                for (int c = 0; c < width; c++) {
                    Object v = row[c];
                    if (v == null) {
                        ps.setNull(c + 1, sqlType(table.columns().get(c).type()));
                    } else if (v instanceof LocalDate d) {
                        ps.setObject(c + 1, d);
                    } else if (v instanceof LocalDateTime t) {
                        ps.setObject(c + 1, t);
                    } else {
                        ps.setObject(c + 1, v);
                    }
                }
                ps.addBatch();
                if (++batch % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }

    private static int sqlType(DataTable.ColumnType type) {
        return switch (type) {
            case NUMBER -> Types.DECIMAL;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TEXT -> Types.VARCHAR;
        };
    }

    private static Result query(Connection reader, String statement, int maxRows, int maxBytes,
                                int timeoutSeconds) throws SQLException {
        long start = System.nanoTime();
        try (Statement st = reader.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            st.setMaxRows(maxRows + 1);
            try (ResultSet rs = st.executeQuery(statement)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> columns = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", md.getColumnLabel(i));
                    col.put("type", md.getColumnTypeName(i));
                    columns.add(col);
                }
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == maxRows) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) row.add(jsonValue(rs.getObject(i)));
                    rows.add(row);
                }
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                return fitBytes(columns, rows, truncated, elapsed, maxBytes);
            }
        }
    }

    /** 결과 JSON 이 상한을 넘으면 행을 줄여 맞춘다. */
    private static Result fitBytes(List<Map<String, Object>> columns, List<List<Object>> rows, boolean truncated,
                                   long elapsed, int maxBytes) {
        Result result = new Result(columns, rows, truncated, elapsed);
        if (size(result) <= maxBytes) return result;
        int low = 0;
        int high = rows.size();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (size(new Result(columns, rows.subList(0, mid), true, elapsed)) <= maxBytes) low = mid;
            else high = mid - 1;
        }
        return new Result(columns, List.copyOf(rows.subList(0, low)), true, elapsed);
    }

    private static int size(Result result) {
        try {
            return MAPPER.writeValueAsString(result.toMap()).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            return Integer.MAX_VALUE;
        }
    }

    static Object jsonValue(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal n) {
            // 400 을 4E+2 로 쓰면 모델이 지수 글로 옮겨 적는다. 끝 0 만 지우고 정수 자리는 남긴다.
            BigDecimal plain = n.stripTrailingZeros();
            return plain.scale() < 0 ? plain.setScale(0) : plain;
        }
        if (v instanceof Number || v instanceof Boolean) {
            if (v instanceof Double d && (d.isNaN() || d.isInfinite())) return d.toString();
            return v;
        }
        if (v instanceof LocalDate d) return d.toString();
        if (v instanceof Date d) return d.toLocalDate().toString();
        if (v instanceof LocalDateTime t) return t.format(TIMESTAMP);
        if (v instanceof Timestamp t) return t.toLocalDateTime().format(TIMESTAMP);
        String s = v.toString();
        return s.length() > MAX_CELL_CHARS ? s.substring(0, MAX_CELL_CHARS) : s;
    }

    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String randomSecret() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
