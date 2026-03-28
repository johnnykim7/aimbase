package com.platform.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테넌트별 HikariCP DataSource를 관리하는 컴포넌트.
 *
 * - Master DB에서 활성 테넌트 목록을 로드하여 DataSource 캐시 구성
 * - 테넌트 프로비저닝 시 새 DataSource 동적 추가
 * - 테넌트 중지(suspend) 시 DataSource 제거 (이후 요청 자동 거부)
 */
@Component
public class TenantDataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceManager.class);

    private final JdbcTemplate masterJdbcTemplate;
    private final Map<String, HikariDataSource> tenantDataSources = new ConcurrentHashMap<>();

    /** Docker 환경에서 DB 호스트를 오버라이드하기 위한 환경변수 */
    @org.springframework.beans.factory.annotation.Value("${tenant.db.host-override:}")
    private String dbHostOverride;

    public TenantDataSourceManager(@Qualifier("masterJdbcTemplate") JdbcTemplate masterJdbcTemplate) {
        this.masterJdbcTemplate = masterJdbcTemplate;
    }

    /**
     * 애플리케이션 시작 시 활성 테넌트 DataSource 초기 로드.
     * TenantDataSourceConfig.afterPropertiesSet()에서 호출.
     */
    public Map<Object, Object> loadAllTenantDataSources() {
        Map<Object, Object> dataSources = new HashMap<>();
        try {
            List<Map<String, Object>> tenants = masterJdbcTemplate.queryForList(
                "SELECT id, db_host, db_port, db_name, db_username, db_password_encrypted " +
                "FROM tenants WHERE status = 'active'"
            );

            for (Map<String, Object> tenant : tenants) {
                String tenantId = (String) tenant.get("id");
                DataSource ds = createDataSource(tenant);
                tenantDataSources.put(tenantId, (HikariDataSource) ds);
                dataSources.put(tenantId, ds);
                log.info("Loaded DataSource for tenant: {}", tenantId);
            }
        } catch (Exception e) {
            log.warn("Could not load tenant DataSources from master DB (may not exist yet): {}", e.getMessage());
        }
        return dataSources;
    }

    /**
     * 새 테넌트 DataSource 추가 (TenantOnboardingService에서 호출).
     */
    public DataSource addTenantDataSource(String tenantId, String host, int port,
                                           String dbName, String username, String password) {
        Map<String, Object> config = new HashMap<>();
        config.put("db_host", host);
        config.put("db_port", port);
        config.put("db_name", dbName);
        config.put("db_username", username);
        config.put("db_password_encrypted", password);

        HikariDataSource ds = createDataSource(config);
        tenantDataSources.put(tenantId, ds);
        log.info("Added DataSource for new tenant: {}", tenantId);
        return ds;
    }

    /**
     * 테넌트 DataSource 제거 (suspend/delete 시).
     */
    public void removeTenantDataSource(String tenantId) {
        HikariDataSource ds = tenantDataSources.remove(tenantId);
        if (ds != null && !ds.isClosed()) {
            ds.close();
            log.info("Removed DataSource for tenant: {}", tenantId);
        }
    }

    public DataSource getTenantDataSource(String tenantId) {
        return tenantDataSources.get(tenantId);
    }

    /**
     * 테넌트 DataSource가 캐시에 없으면 Master DB에서 조회하여 동적 생성.
     * MCP SSE 연결 시 endpoint 이벤트 발행 전에 DataSource를 보장하기 위해 사용.
     */
    public void ensureDataSource(String tenantId) {
        if (tenantDataSources.containsKey(tenantId)) {
            return;
        }
        try {
            List<Map<String, Object>> rows = masterJdbcTemplate.queryForList(
                "SELECT db_host, db_port, db_name, db_username, db_password_encrypted " +
                "FROM tenants WHERE id = ? AND status = 'active'",
                tenantId
            );
            if (rows.isEmpty()) {
                log.warn("ensureDataSource: tenant '{}' not found or inactive", tenantId);
                return;
            }
            Map<String, Object> tenant = rows.get(0);
            HikariDataSource ds = createDataSource(tenant);
            tenantDataSources.put(tenantId, ds);
            log.info("ensureDataSource: dynamically created DataSource for tenant '{}'", tenantId);
        } catch (Exception e) {
            log.error("ensureDataSource: failed for tenant '{}': {}", tenantId, e.getMessage());
        }
    }

    public Map<String, HikariDataSource> getAllCachedDataSources() {
        return Map.copyOf(tenantDataSources);
    }

    private HikariDataSource createDataSource(Map<String, Object> tenant) {
        HikariConfig config = new HikariConfig();
        String host = (dbHostOverride != null && !dbHostOverride.isBlank())
                ? dbHostOverride
                : (String) tenant.get("db_host");
        Object portObj = tenant.get("db_port");
        int port = portObj instanceof Number n ? n.intValue() : Integer.parseInt(portObj.toString());
        String dbName = (String) tenant.get("db_name");
        String username = (String) tenant.get("db_username");
        String password = (String) tenant.get("db_password_encrypted");

        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName));
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);

        return new HikariDataSource(config);
    }
}
