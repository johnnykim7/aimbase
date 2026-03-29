package com.platform.app;

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
 * App(소비앱)별 HikariCP DataSource를 관리하는 컴포넌트.
 *
 * TenantDataSourceManager와 동일한 패턴:
 * - Master DB에서 활성 App 목록을 로드하여 DataSource 캐시 구성
 * - App 프로비저닝 시 새 DataSource 동적 추가
 * - App 중지/삭제 시 DataSource 제거
 */
@Component
public class AppDataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(AppDataSourceManager.class);

    private final JdbcTemplate masterJdbcTemplate;
    private final Map<String, HikariDataSource> appDataSources = new ConcurrentHashMap<>();

    public AppDataSourceManager(@Qualifier("masterJdbcTemplate") JdbcTemplate masterJdbcTemplate) {
        this.masterJdbcTemplate = masterJdbcTemplate;
    }

    /**
     * 애플리케이션 시작 시 활성 App DataSource 초기 로드.
     */
    public Map<Object, Object> loadAllAppDataSources() {
        Map<Object, Object> dataSources = new HashMap<>();
        try {
            List<Map<String, Object>> apps = masterJdbcTemplate.queryForList(
                "SELECT id, db_host, db_port, db_name, db_username, db_password_encrypted " +
                "FROM apps WHERE status = 'active'"
            );

            for (Map<String, Object> app : apps) {
                String appId = (String) app.get("id");
                DataSource ds = createDataSource(app);
                appDataSources.put(appId, (HikariDataSource) ds);
                dataSources.put(appId, ds);
                log.info("Loaded DataSource for app: {}", appId);
            }
        } catch (Exception e) {
            log.warn("Could not load app DataSources from master DB (may not exist yet): {}", e.getMessage());
        }
        return dataSources;
    }

    public DataSource addAppDataSource(String appId, String host, int port,
                                        String dbName, String username, String password) {
        Map<String, Object> config = new HashMap<>();
        config.put("db_host", host);
        config.put("db_port", port);
        config.put("db_name", dbName);
        config.put("db_username", username);
        config.put("db_password_encrypted", password);

        HikariDataSource ds = createDataSource(config);
        appDataSources.put(appId, ds);
        log.info("Added DataSource for new app: {}", appId);
        return ds;
    }

    public void removeAppDataSource(String appId) {
        HikariDataSource ds = appDataSources.remove(appId);
        if (ds != null && !ds.isClosed()) {
            ds.close();
            log.info("Removed DataSource for app: {}", appId);
        }
    }

    public DataSource getAppDataSource(String appId) {
        return appDataSources.get(appId);
    }

    /**
     * tenantId로부터 소속 appId를 조회하여 App DataSource를 반환.
     */
    public DataSource getAppDataSourceForTenant(String tenantId) {
        try {
            String appId = masterJdbcTemplate.queryForObject(
                "SELECT app_id FROM tenants WHERE id = ?",
                String.class, tenantId
            );
            if (appId != null) {
                return appDataSources.get(appId);
            }
        } catch (Exception e) {
            log.debug("No app_id found for tenant: {}", tenantId);
        }
        return null;
    }

    public Map<String, HikariDataSource> getAllCachedDataSources() {
        return Map.copyOf(appDataSources);
    }

    private HikariDataSource createDataSource(Map<String, Object> config) {
        HikariConfig hikariConfig = new HikariConfig();
        String host = (String) config.get("db_host");
        Object portObj = config.get("db_port");
        int port = portObj instanceof Number n ? n.intValue() : Integer.parseInt(portObj.toString());
        String dbName = (String) config.get("db_name");
        String username = (String) config.get("db_username");
        String password = (String) config.get("db_password_encrypted");

        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName));
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setMaximumPoolSize(5);  // App DB는 공통 리소스 조회용이므로 작은 풀
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(30_000);
        hikariConfig.setIdleTimeout(600_000);
        hikariConfig.setMaxLifetime(1_800_000);

        return new HikariDataSource(hikariConfig);
    }
}
