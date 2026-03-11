package com.platform.action.write;

import com.platform.action.model.HealthStatus;
import com.platform.action.model.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL Write Adapter.
 * 플랫폼의 기본 PostgreSQL 연결(JdbcTemplate)을 사용.
 * 외부 연결이 등록된 경우에는 AdapterRegistry를 통해 별도 DataSource를 주입받는 방식으로 확장 가능.
 */
@Component
public class PostgreSQLAdapter implements WriteAdapter {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public PostgreSQLAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getId() {
        return "postgresql";
    }

    @Override
    public void connect(Map<String, Object> config) {
        // Spring이 관리하는 DataSource이므로 별도 connect 불필요
        log.debug("PostgreSQLAdapter using Spring-managed DataSource");
    }

    @Override
    public void disconnect() {
        // 관리 불필요
    }

    @Override
    public HealthStatus healthCheck() {
        long start = Instant.now().toEpochMilli();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return HealthStatus.healthy(Instant.now().toEpochMilli() - start);
        } catch (Exception e) {
            log.warn("PostgreSQL health check failed", e);
            return HealthStatus.unhealthy();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WriteResult write(String destination, Object data, Map<String, Object> options) {
        try {
            if (!(data instanceof Map<?, ?> dataMap)) {
                return WriteResult.failure("Data must be a Map<String, Object>");
            }
            Map<String, Object> row = (Map<String, Object>) dataMap;

            List<String> columns = new ArrayList<>(row.keySet());
            String columnsSql = String.join(", ", columns);
            String placeholders = columns.stream().map(c -> "?").reduce((a, b) -> a + ", " + b).orElse("");
            String sql = "INSERT INTO " + destination + " (" + columnsSql + ") VALUES (" + placeholders + ")";

            Object[] values = columns.stream().map(row::get).toArray();
            int affected = jdbcTemplate.update(sql, values);
            return WriteResult.success(affected);
        } catch (Exception e) {
            log.error("PostgreSQL write error to {}: {}", destination, e.getMessage());
            return WriteResult.failure(e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object read(String destination, Object query) {
        try {
            if (query instanceof String sql) {
                return jdbcTemplate.queryForList(sql);
            }
            if (query instanceof Map<?, ?> queryMap) {
                Map<String, Object> conditions = (Map<String, Object>) queryMap;
                String whereSql = conditions.entrySet().stream()
                        .map(e -> e.getKey() + " = ?")
                        .reduce((a, b) -> a + " AND " + b)
                        .orElse("1=1");
                String sql = "SELECT * FROM " + destination + " WHERE " + whereSql;
                Object[] params = conditions.values().toArray();
                return jdbcTemplate.queryForList(sql, params);
            }
            return jdbcTemplate.queryForList("SELECT * FROM " + destination);
        } catch (Exception e) {
            log.error("PostgreSQL read error from {}: {}", destination, e.getMessage());
            return List.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WriteResult update(String destination, Object query, Object data) {
        try {
            Map<String, Object> dataMap = (Map<String, Object>) data;
            Map<String, Object> queryMap = (Map<String, Object>) query;

            String setSql = dataMap.keySet().stream()
                    .map(k -> k + " = ?")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            String whereSql = queryMap.keySet().stream()
                    .map(k -> k + " = ?")
                    .reduce((a, b) -> a + " AND " + b)
                    .orElse("1=1");

            String sql = "UPDATE " + destination + " SET " + setSql + " WHERE " + whereSql;

            List<Object> params = new ArrayList<>(dataMap.values());
            params.addAll(queryMap.values());

            int affected = jdbcTemplate.update(sql, params.toArray());
            return WriteResult.success(affected);
        } catch (Exception e) {
            log.error("PostgreSQL update error on {}: {}", destination, e.getMessage());
            return WriteResult.failure(e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WriteResult delete(String destination, Object query) {
        try {
            Map<String, Object> queryMap = (Map<String, Object>) query;

            String whereSql = queryMap.keySet().stream()
                    .map(k -> k + " = ?")
                    .reduce((a, b) -> a + " AND " + b)
                    .orElse("1=1");

            String sql = "DELETE FROM " + destination + " WHERE " + whereSql;
            int affected = jdbcTemplate.update(sql, queryMap.values().toArray());
            return WriteResult.success(affected);
        } catch (Exception e) {
            log.error("PostgreSQL delete error on {}: {}", destination, e.getMessage());
            return WriteResult.failure(e.getMessage());
        }
    }
}
