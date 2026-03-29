package com.platform.app.onboarding;

import com.platform.app.AppDataSourceManager;
import com.platform.config.FlywayMultiTenantConfig;
import com.platform.domain.master.AppAdminEntity;
import com.platform.domain.master.AppEntity;
import com.platform.repository.master.AppAdminRepository;
import com.platform.repository.master.AppRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * App(소비앱) 프로비저닝 오케스트레이터.
 *
 * 프로비저닝 플로우:
 *   1. Master DB에 AppEntity INSERT
 *   2. PostgreSQL에 aimbase_app_<appId> 데이터베이스 생성
 *   3. pgvector extension 설치
 *   4. Flyway app 마이그레이션 실행 (tenant와 동일 스키마)
 *   5. 초기 App Admin 계정 생성
 *   6. AppDataSourceManager 캐시에 등록
 *
 * 실패 시 롤백: Master DB 레코드 삭제 + DB DROP 시도
 */
@Service
public class AppOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(AppOnboardingService.class);

    private final AppRepository appRepository;
    private final AppAdminRepository appAdminRepository;
    private final AppDataSourceManager appDataSourceManager;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${platform.default-db-password:platform}")
    private String defaultDbPassword;

    public AppOnboardingService(AppRepository appRepository,
                                 AppAdminRepository appAdminRepository,
                                 AppDataSourceManager appDataSourceManager) {
        this.appRepository = appRepository;
        this.appAdminRepository = appAdminRepository;
        this.appDataSourceManager = appDataSourceManager;
    }

    public AppOnboardingResult provision(AppOnboardingRequest request) {
        log.info("Starting app provisioning: {}", request.appId());

        // Step 1: Master DB에 App 레코드 저장
        AppEntity app = createAppEntity(request);
        appRepository.save(app);

        try {
            // Step 2: PostgreSQL 데이터베이스 생성
            String dbName = "aimbase_app_" + request.appId();
            createAppDatabase(request.dbHost(), request.dbPort(), dbName, request.dbUsername(), request.dbPassword());

            // Step 3~4: pgvector + Flyway 마이그레이션 (tenant 스키마 재사용)
            HikariDataSource tempDs = createTemporaryDataSource(
                request.dbHost(), request.dbPort(), dbName,
                request.dbUsername(), request.dbPassword()
            );
            runAppMigration(tempDs, dbName);
            tempDs.close();

            // Step 5: AppDataSourceManager 캐시에 등록
            appDataSourceManager.addAppDataSource(
                request.appId(), request.dbHost(), request.dbPort(),
                dbName, request.dbUsername(), request.dbPassword()
            );

            // Step 6: 초기 App Admin 계정 생성
            if (request.ownerEmail() != null && request.ownerPassword() != null) {
                AppAdminEntity admin = new AppAdminEntity();
                admin.setAppId(request.appId());
                admin.setEmail(request.ownerEmail());
                admin.setPasswordHash(passwordEncoder.encode(request.ownerPassword()));
                admin.setDisplayName("App Owner");
                admin.setRole("app_admin");
                appAdminRepository.save(admin);
            }

            // Master DB App 상태 업데이트
            app.setDbName(dbName);
            app.setStatus("active");
            appRepository.save(app);

            log.info("App provisioning completed: {}", request.appId());
            return AppOnboardingResult.success(request.appId(), dbName);

        } catch (Exception e) {
            log.error("App provisioning failed for {}: {}", request.appId(), e.getMessage(), e);
            rollback(request.appId());
            throw new RuntimeException("App provisioning failed: " + e.getMessage(), e);
        }
    }

    public void deprovision(String appId) {
        log.info("Deprovisioning app: {}", appId);

        AppEntity app = appRepository.findById(appId)
            .orElseThrow(() -> new IllegalArgumentException("App not found: " + appId));

        // DataSource 캐시에서 제거
        appDataSourceManager.removeAppDataSource(appId);

        // App DB DROP
        String dbName = app.getDbName();
        if (dbName != null) {
            try (HikariDataSource adminDs = createTemporaryDataSource(
                    app.getDbHost(), app.getDbPort(), "postgres",
                    app.getDbUsername(), defaultDbPassword)) {
                JdbcTemplate adminJdbc = new JdbcTemplate(adminDs);
                adminJdbc.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "'"
                );
                adminJdbc.execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
                log.info("Dropped app database: {}", dbName);
            } catch (Exception e) {
                log.error("Failed to drop app database {}: {}", dbName, e.getMessage());
            }
        }

        // Master 레코드 삭제
        appAdminRepository.findByAppId(appId).forEach(admin -> appAdminRepository.delete(admin));
        appRepository.deleteById(appId);
        log.info("App deprovisioned: {}", appId);
    }

    // ─── Private Helpers ───────────────────────────────────────────────

    private AppEntity createAppEntity(AppOnboardingRequest request) {
        AppEntity app = new AppEntity();
        app.setId(request.appId());
        app.setName(request.name());
        app.setDescription(request.description());
        app.setStatus("provisioning");
        app.setDbHost(request.dbHost());
        app.setDbPort(request.dbPort());
        app.setDbName("aimbase_app_" + request.appId());
        app.setDbUsername(request.dbUsername());
        app.setDbPasswordEncrypted(passwordEncoder.encode(request.dbPassword()));
        app.setOwnerEmail(request.ownerEmail());
        app.setMaxTenants(request.maxTenants() != null ? request.maxTenants() : 100);
        return app;
    }

    private void createAppDatabase(String host, int port, String dbName, String dbUser, String dbPassword) {
        try (HikariDataSource adminDs = createTemporaryDataSource(host, port, "postgres", dbUser, dbPassword)) {
            JdbcTemplate adminJdbc = new JdbcTemplate(adminDs);
            adminJdbc.execute("CREATE DATABASE \"" + dbName + "\"");
            adminJdbc.execute("GRANT ALL PRIVILEGES ON DATABASE \"" + dbName + "\" TO " + dbUser);
            log.info("Created app database: {}", dbName);
        }
    }

    private void runAppMigration(DataSource appDs, String dbName) {
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        appJdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        // App DB는 tenant 스키마를 재사용 (동일 테이블 구조 → fallback 조회 가능)
        FlywayMultiTenantConfig.migrateAppDatabase(appDs);
        log.info("App DB migration completed for: {}", dbName);
    }

    private HikariDataSource createTemporaryDataSource(String host, int port, String dbName,
                                                        String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    private void rollback(String appId) {
        try {
            appDataSourceManager.removeAppDataSource(appId);
            String dbName = "aimbase_app_" + appId;

            appRepository.findById(appId).ifPresent(app -> {
                try (HikariDataSource adminDs = createTemporaryDataSource(
                        app.getDbHost(), app.getDbPort(), "postgres",
                        app.getDbUsername(), defaultDbPassword)) {
                    new JdbcTemplate(adminDs).execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
                } catch (Exception e) {
                    log.error("Failed to drop DB during rollback for app {}: {}", appId, e.getMessage());
                }
            });
            appRepository.deleteById(appId);
            log.info("Rollback completed for app: {}", appId);
        } catch (Exception e) {
            log.error("Rollback failed for app {}: {}", appId, e.getMessage());
        }
    }
}
