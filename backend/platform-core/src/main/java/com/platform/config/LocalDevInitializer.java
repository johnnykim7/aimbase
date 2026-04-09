package com.platform.config;

import com.platform.auth.JwtProvider;
import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.TenantRepository;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * local 프로파일 전용 개발 환경 자동 초기화.
 *
 * 앱 시작 시:
 * 1. "dev" 테넌트가 없으면 Master DB에 등록 + Tenant DB Flyway 실행
 * 2. admin 사용자 패스워드 설정 (로그인 가능하도록)
 * 3. JWT 토큰 발급 후 로그 출력 (복사해서 바로 사용)
 */
@Profile("local")
@Component
public class LocalDevInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDevInitializer.class);

    private static final String TENANT_ID = "tenant_dev";
    private static final String TENANT_NAME = "Development Tenant";
    private static final String ADMIN_EMAIL = "admin@dev.local";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String ADMIN_USER_ID = "admin-tenant_dev";
    private static final String AXOPM_API_KEY = "plat-axopm-dev-test-key-00000001";
    private static final String AXOPM_API_KEY_ID = "ak-axopm-dev";

    private final TenantRepository tenantRepository;
    private final TenantDataSourceManager dataSourceManager;
    private final JwtProvider jwtProvider;
    private final JdbcTemplate masterJdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${platform.default-db-host:localhost}")
    private String dbHost;

    @Value("${platform.default-db-port:5432}")
    private int dbPort;

    @Value("${platform.default-db-username:platform}")
    private String dbUsername;

    @Value("${platform.default-db-password:platform}")
    private String dbPassword;

    public LocalDevInitializer(TenantRepository tenantRepository,
                                TenantDataSourceManager dataSourceManager,
                                JwtProvider jwtProvider,
                                @org.springframework.beans.factory.annotation.Qualifier("masterJdbcTemplate") JdbcTemplate masterJdbcTemplate) {
        this.tenantRepository = tenantRepository;
        this.dataSourceManager = dataSourceManager;
        this.jwtProvider = jwtProvider;
        this.masterJdbcTemplate = masterJdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureDevTenant();
            ensureAdminUser();
            ensureAxopmApiKey();
            printDevToken();
        } catch (Exception e) {
            log.warn("LocalDevInitializer failed (non-fatal): {}", e.getMessage());
        }
    }

    private void ensureDevTenant() {
        if (tenantRepository.existsById(TENANT_ID)) {
            log.info("[LocalDev] Tenant '{}' already exists, running Flyway migration...", TENANT_ID);
            // DataSourceManager에 등록 (재시작 시 캐시 비어있으므로)
            DataSource ds = dataSourceManager.addTenantDataSource(
                    TENANT_ID, dbHost, dbPort, "aimbase_tenant_dev", dbUsername, dbPassword);
            FlywayMultiTenantConfig.migrateTenantDatabase(ds);
            // Admin 시드 (이전 실행에서 실패했을 수 있으므로 항상 보장)
            JdbcTemplate tenantJdbc = new JdbcTemplate(ds);
            tenantJdbc.update(
                    "INSERT INTO users (id, email, name, role_id, is_active) " +
                    "VALUES (?, ?, ?, 'admin', true) ON CONFLICT (id) DO NOTHING",
                    ADMIN_USER_ID, ADMIN_EMAIL, "Admin");
            return;
        }

        log.info("[LocalDev] Creating dev tenant '{}'...", TENANT_ID);

        // 1. Master DB에 테넌트 레코드
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        tenant.setName(TENANT_NAME);
        tenant.setStatus("active");
        tenant.setDbHost(dbHost);
        tenant.setDbPort(dbPort);
        tenant.setDbName("aimbase_tenant_dev");
        tenant.setDbUsername(dbUsername);
        tenant.setDbPasswordEncrypted(passwordEncoder.encode(dbPassword));
        tenant.setAdminEmail(ADMIN_EMAIL);
        tenantRepository.save(tenant);

        // 2. DataSourceManager에 등록
        DataSource ds = dataSourceManager.addTenantDataSource(
                TENANT_ID, dbHost, dbPort, "aimbase_tenant_dev", dbUsername, dbPassword);

        // 3. pgvector + Flyway
        JdbcTemplate tenantJdbc = new JdbcTemplate(ds);
        tenantJdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        FlywayMultiTenantConfig.migrateTenantDatabase(ds);

        // 4. Admin 시드
        tenantJdbc.update(
                "INSERT INTO users (id, email, name, role_id, is_active) " +
                "VALUES (?, ?, ?, 'admin', true) ON CONFLICT (id) DO NOTHING",
                ADMIN_USER_ID, ADMIN_EMAIL, "Admin");

        log.info("[LocalDev] Tenant '{}' created successfully", TENANT_ID);
    }

    private void ensureAdminUser() {
        DataSource ds = dataSourceManager.getTenantDataSource(TENANT_ID);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // password_hash가 null이면 설정
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class, ADMIN_USER_ID);

        if (hash == null || hash.isBlank()) {
            String encoded = passwordEncoder.encode(ADMIN_PASSWORD);
            jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoded, ADMIN_USER_ID);
            log.info("[LocalDev] Admin password set for {}", ADMIN_EMAIL);
        }
    }

    private void ensureAxopmApiKey() {
        // Master DB에 api_keys 시딩 (CR-025)
        Integer count = masterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE id = ?", Integer.class, AXOPM_API_KEY_ID);
        if (count != null && count > 0) {
            log.info("[LocalDev] AXOPM API Key already exists (id={})", AXOPM_API_KEY_ID);
            return;
        }

        String hash = sha256(AXOPM_API_KEY);
        String prefix = AXOPM_API_KEY.substring(0, 8);
        masterJdbcTemplate.update(
                "INSERT INTO api_keys (id, name, key_hash, key_prefix, tenant_id, domain_app, is_active, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, true, ?)",
                AXOPM_API_KEY_ID, "AXOPM 연동용 (dev)", hash, prefix, TENANT_ID, "axopm", ADMIN_EMAIL);

        log.info("[LocalDev] AXOPM API Key seeded: {}", AXOPM_API_KEY);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void printDevToken() {
        String token = jwtProvider.generateAccessToken(ADMIN_USER_ID, ADMIN_EMAIL, TENANT_ID, "admin");

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  LOCAL DEV CREDENTIALS                                      ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  Tenant: {}                                        ║", TENANT_ID);
        log.info("║  Email:  {}                                  ║", ADMIN_EMAIL);
        log.info("║  Pass:   {}                                        ║", ADMIN_PASSWORD);
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  Bearer Token (30min):                                      ║");
        log.info("║  {}  ║", token.substring(0, Math.min(58, token.length())));
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  AXOPM API Key (X-API-Key):                                 ║");
        log.info("║  {}  ║", AXOPM_API_KEY);
        log.info("╚══════════════════════════════════════════════════════════════╝");
        log.info("[LocalDev] Full token: {}", token);
        log.info("[LocalDev] AXOPM API Key: {}", AXOPM_API_KEY);
    }
}
