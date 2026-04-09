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

    public Map<String, HikariDataSource> getAllCachedDataSources() {
        return Map.copyOf(tenantDataSources);
    }

    /** TENANT_DB_HOST 환경변수가 설정되어 있으면 DB에 저장된 db_host를 오버라이드 */
    private static final String DB_HOST_OVERRIDE = System.getenv("TENANT_DB_HOST");

    private HikariDataSource createDataSource(Map<String, Object> tenant) {
        HikariConfig config = new HikariConfig();
        String host = DB_HOST_OVERRIDE != null && !DB_HOST_OVERRIDE.isBlank()
                ? DB_HOST_OVERRIDE : (String) tenant.get("db_host");
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
