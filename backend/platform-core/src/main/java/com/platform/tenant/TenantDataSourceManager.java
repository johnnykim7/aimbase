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

    /**
     * CR-122: 테넌트 DB 접속 비밀번호 단일 출처.
     *
     * <p>테넌트마다 DB 계정을 따로 만들지 않는다 — 전 테넌트가 {@code db_username=platform} 한 계정을
     * 공유하며, 온보딩의 DB 생성/삭제/마이그레이션도 모두 이 값으로 접속한다
     * ({@code TenantOnboardingService} 133·248번 줄). 따라서 접속 비밀번호의 출처는 이 설정값이며
     * {@code tenants.db_password_encrypted} 컬럼이 아니다.</p>
     */
    @org.springframework.beans.factory.annotation.Value("${platform.default-db-password:platform}")
    private String defaultDbPassword;

    public TenantDataSourceManager(@Qualifier("masterJdbcTemplate") JdbcTemplate masterJdbcTemplate) {
        this.masterJdbcTemplate = masterJdbcTemplate;
    }

    /**
     * 애플리케이션 시작 시 활성 테넌트 DataSource 초기 로드.
     * TenantDataSourceConfig.afterPropertiesSet()에서 호출.
     */
    public Map<Object, Object> loadAllTenantDataSources() {
        Map<Object, Object> dataSources = new HashMap<>();
        List<Map<String, Object>> tenants;
        try {
            tenants = masterJdbcTemplate.queryForList(
                "SELECT id, db_host, db_port, db_name, db_username " +
                "FROM tenants WHERE status = 'active'"
            );
        } catch (Exception e) {
            log.warn("Could not load tenant DataSources from master DB (may not exist yet): {}", e.getMessage());
            return dataSources;
        }

        // CR-122: try 를 루프 안으로 — 한 테넌트 실패가 이후 테넌트 로드를 통째로 중단시키던 문제 수정.
        // (운영 실측: 6번째 테넌트 접속 실패로 bp_wes/workmap 이 아예 로드되지 않아 원격 도구 동기화가 멈췄다.)
        for (Map<String, Object> tenant : tenants) {
            String tenantId = (String) tenant.get("id");
            try {
                DataSource ds = createDataSource(tenant);
                tenantDataSources.put(tenantId, (HikariDataSource) ds);
                dataSources.put(tenantId, ds);
                log.info("Loaded DataSource for tenant: {}", tenantId);
            } catch (Exception e) {
                log.error("Failed to load DataSource for tenant {} — skipping: {}", tenantId, e.getMessage());
            }
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
        // CR-122: 호출자가 넘긴 평문 비밀번호. createDataSource 가 이 키를 설정값보다 우선한다.
        config.put("db_password", password);

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

    /**
     * CR-142: 테넌트의 물리 DB 이름 조회 (RAG 사이드카 라우팅용).
     *
     * <p>사이드카는 테넌트 개념이 없어 단일 {@code DB_NAME} 으로 고정되어 있다. 호출 시 이 값을
     * 인자로 넘겨 테넌트별 DB 로 라우팅한다. 이름을 {@code "aimbase_" + tenantId} 로 <b>조립하지
     * 않는다</b> — 2026-08-20 에 그 조립 규칙의 대소문자 불일치로 사이드카 DB 연결이 전면 실패한
     * 이력이 있고, {@code shopai-store-a} 처럼 하이픈이 든 테넌트 ID 도 있다. 정본은 master 의
     * {@code tenants.db_name} 이며, 여기서는 이미 그 값으로 만들어진 풀의 JDBC URL 에서 되읽는다.</p>
     *
     * @return DB 이름. 해당 테넌트의 DataSource 가 없으면 {@code null}
     */
    public String getDbName(String tenantId) {
        HikariDataSource ds = tenantDataSources.get(tenantId);
        if (ds == null) {
            return null;
        }
        String jdbcUrl = ds.getJdbcUrl();
        if (jdbcUrl == null) {
            return null;
        }
        int slash = jdbcUrl.lastIndexOf('/');
        if (slash < 0 || slash == jdbcUrl.length() - 1) {
            return null;
        }
        String name = jdbcUrl.substring(slash + 1);
        int q = name.indexOf('?');
        return q >= 0 ? name.substring(0, q) : name;
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
        // CR-122: 접속 비밀번호는 설정값(platform.default-db-password)이 단일 출처.
        // 과거엔 tenants.db_password_encrypted 를 그대로 썼으나, 온보딩이 그 컬럼에 BCrypt 해시를
        // 저장하므로(TenantOnboardingService 167번 줄) 복원 불가라 기동 시 접속이 항상 실패했다.
        // addTenantDataSource 처럼 호출자가 평문을 넘긴 경우에만 그 값을 우선한다.
        String override = (String) tenant.get("db_password");
        String password = (override != null && !override.isBlank()) ? override : defaultDbPassword;

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
