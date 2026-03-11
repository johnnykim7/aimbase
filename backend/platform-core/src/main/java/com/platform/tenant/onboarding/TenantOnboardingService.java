package com.platform.tenant.onboarding;

import com.platform.config.FlywayMultiTenantConfig;
import com.platform.domain.master.SubscriptionEntity;
import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.tenant.TenantDataSourceManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;

/**
 * 테넌트 프로비저닝 오케스트레이터.
 *
 * 5단계 프로비저닝 플로우:
 *   1. Master DB에 TenantEntity INSERT
 *   2. PostgreSQL에 새 데이터베이스 생성
 *   3. pgvector extension 설치
 *   4. Flyway tenant 마이그레이션 실행
 *   5. 초기 Admin 계정 + 기본 역할/정책 시드 데이터 삽입
 *   6. TenantDataSourceManager 캐시에 등록
 *
 * 실패 시 롤백: Master DB 레코드 삭제 + DB DROP 시도
 */
@Service
public class TenantOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(TenantOnboardingService.class);

    private final TenantRepository tenantRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TenantDataSourceManager dataSourceManager;
    private final JdbcTemplate masterJdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // tenant DB 서버의 기본 크레덴셜 (rollback/deprovision에서 admin 접속용)
    @Value("${platform.default-db-password:platform}")
    private String defaultDbPassword;

    public TenantOnboardingService(TenantRepository tenantRepository,
                                    SubscriptionRepository subscriptionRepository,
                                    TenantDataSourceManager dataSourceManager,
                                    @Qualifier("masterJdbcTemplate") JdbcTemplate masterJdbcTemplate) {
        this.tenantRepository = tenantRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.dataSourceManager = dataSourceManager;
        this.masterJdbcTemplate = masterJdbcTemplate;
    }

    /**
     * 테넌트 전체 프로비저닝 실행.
     *
     * @param request 테넌트 생성 요청 (id, name, dbConfig, adminEmail, plan)
     * @return 프로비저닝 결과
     */
    // @Transactional 제거: CREATE DATABASE는 트랜잭션 블록 안에서 실행 불가 (PostgreSQL 제약).
    // 개별 repository.save() 는 Spring Data JPA 자체 트랜잭션을 사용하며,
    // 실패 시 rollback() 메서드로 수동 정리한다.
    public TenantOnboardingResult provision(TenantOnboardingRequest request) {
        log.info("Starting tenant provisioning: {}", request.tenantId());

        // Step 1: Master DB에 테넌트 레코드 저장
        TenantEntity tenant = createTenantEntity(request);
        tenantRepository.save(tenant);

        // Step 2~6: DB 생성 이후는 Master Transaction 밖에서 수행
        try {
            // Step 2: PostgreSQL 데이터베이스 생성 (tenant DB 서버에 직접 연결)
            String dbName = "aimbase_" + request.tenantId();
            createTenantDatabase(request.dbHost(), request.dbPort(), dbName, request.dbUsername(), request.dbPassword());

            // Step 3~4: pgvector + Flyway 마이그레이션
            HikariDataSource tenantDs = createTemporaryDataSource(
                request.dbHost(), request.dbPort(), dbName,
                request.dbUsername(), request.dbPassword()
            );
            runTenantMigration(tenantDs, dbName);
            tenantDs.close();

            // Step 5: 시드 데이터 (Admin 계정, 기본 역할)
            DataSource registeredDs = dataSourceManager.addTenantDataSource(
                request.tenantId(), request.dbHost(), request.dbPort(),
                dbName, request.dbUsername(), request.dbPassword()
            );
            seedInitialData(registeredDs, request);

            // Step 6: 구독 정보 저장
            SubscriptionEntity subscription = createSubscription(request);
            subscriptionRepository.save(subscription);

            // Master DB 테넌트 상태 업데이트
            tenant.setDbName(dbName);
            tenant.setStatus("active");
            tenantRepository.save(tenant);

            log.info("Tenant provisioning completed: {}", request.tenantId());
            return TenantOnboardingResult.success(request.tenantId());

        } catch (Exception e) {
            log.error("Tenant provisioning failed for {}: {}", request.tenantId(), e.getMessage(), e);
            // 롤백 시도
            rollback(request.tenantId());
            throw new RuntimeException("Tenant provisioning failed: " + e.getMessage(), e);
        }
    }

    /**
     * 테넌트 DB 및 Master 레코드 삭제 (테넌트 삭제 시).
     */
    // @Transactional 제거: DROP DATABASE도 트랜잭션 블록 안에서 실행 불가
    public void deprovision(String tenantId) {
        log.info("Deprovisioning tenant: {}", tenantId);

        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        // DataSource 캐시에서 제거
        dataSourceManager.removeTenantDataSource(tenantId);

        // Tenant DB DROP (tenant DB 서버에 직접 연결)
        String dbName = tenant.getDbName();
        if (dbName != null) {
            try (HikariDataSource adminDs = createTemporaryDataSource(
                    tenant.getDbHost(), tenant.getDbPort(), "postgres",
                    tenant.getDbUsername(), defaultDbPassword)) {
                JdbcTemplate adminJdbc = new JdbcTemplate(adminDs);
                adminJdbc.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "'"
                );
                adminJdbc.execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
                log.info("Dropped tenant database: {}", dbName);
            } catch (Exception e) {
                log.error("Failed to drop tenant database {}: {}", dbName, e.getMessage());
            }
        }

        // Master 레코드 삭제
        tenantRepository.deleteById(tenantId);
        subscriptionRepository.deleteById(tenantId);
        log.info("Tenant deprovisioned: {}", tenantId);
    }

    // ─── Private Helpers ───────────────────────────────────────────────

    private TenantEntity createTenantEntity(TenantOnboardingRequest request) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(request.tenantId());
        tenant.setName(request.name());
        tenant.setStatus("provisioning");
        tenant.setDbHost(request.dbHost());
        tenant.setDbPort(request.dbPort());
        tenant.setDbName("aimbase_" + request.tenantId());
        tenant.setDbUsername(request.dbUsername());
        tenant.setDbPasswordEncrypted(passwordEncoder.encode(request.dbPassword()));
        tenant.setAdminEmail(request.adminEmail());
        return tenant;
    }

    private SubscriptionEntity createSubscription(TenantOnboardingRequest request) {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setTenantId(request.tenantId());
        sub.setPlan(request.plan() != null ? request.plan() : "free");
        return sub;
    }

    private void createTenantDatabase(String host, int port, String dbName, String dbUser, String dbPassword) {
        // tenant DB 서버에 직접 연결하여 새 DB 생성 (masterJdbcTemplate은 postgres-master용)
        // 'postgres' 기본 DB로 접속하여 CREATE DATABASE 실행 (PostgreSQL 기본 DB는 항상 존재)
        try (HikariDataSource adminDs = createTemporaryDataSource(host, port, "postgres", dbUser, dbPassword)) {
            JdbcTemplate adminJdbc = new JdbcTemplate(adminDs);
            adminJdbc.execute("CREATE DATABASE \"" + dbName + "\"");
            adminJdbc.execute("GRANT ALL PRIVILEGES ON DATABASE \"" + dbName + "\" TO " + dbUser);
            log.info("Created tenant database: {}", dbName);
        }
    }

    private void runTenantMigration(DataSource tenantDs, String dbName) {
        // pgvector extension 설치
        JdbcTemplate tenantJdbc = new JdbcTemplate(tenantDs);
        tenantJdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        // Flyway 마이그레이션 실행
        FlywayMultiTenantConfig.migrateTenantDatabase(tenantDs);
        log.info("Tenant DB migration completed for: {}", dbName);
    }

    private void seedInitialData(DataSource tenantDs, TenantOnboardingRequest request) {
        JdbcTemplate tenantJdbc = new JdbcTemplate(tenantDs);

        // 기본 역할은 V12 Flyway 마이그레이션에서 이미 시드됨 (admin, operator, viewer)
        // 초기 Admin 계정 (users 테이블 스키마: id, email, name, role_id, api_key_hash, is_active)
        tenantJdbc.update(
            "INSERT INTO users (id, email, name, role_id, is_active) " +
            "VALUES (?, ?, ?, 'admin', true) ON CONFLICT (id) DO NOTHING",
            "admin-" + request.tenantId(),
            request.adminEmail(),
            "Admin"
        );

        log.info("Seeded initial data for tenant: {}", request.tenantId());
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

    private void rollback(String tenantId) {
        try {
            dataSourceManager.removeTenantDataSource(tenantId);
            String dbName = "aimbase_" + tenantId;

            // rollback 시 tenant entity에서 DB 호스트 정보 조회
            tenantRepository.findById(tenantId).ifPresent(tenant -> {
                try (HikariDataSource adminDs = createTemporaryDataSource(
                        tenant.getDbHost(), tenant.getDbPort(), "postgres",
                        tenant.getDbUsername(), defaultDbPassword)) {
                    new JdbcTemplate(adminDs).execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
                } catch (Exception e) {
                    log.error("Failed to drop DB during rollback for tenant {}: {}", tenantId, e.getMessage());
                }
            });
            tenantRepository.deleteById(tenantId);
            log.info("Rollback completed for tenant: {}", tenantId);
        } catch (Exception e) {
            log.error("Rollback failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
