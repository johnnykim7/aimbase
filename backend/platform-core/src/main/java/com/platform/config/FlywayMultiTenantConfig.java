package com.platform.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Flyway 이중 마이그레이션 경로 설정.
 *
 * - Master DB: classpath:db/migration/master/
 * - Tenant DB: classpath:db/migration/tenant/ (TenantOnboardingService에서 동적 실행)
 *
 * Spring Boot 자동 Flyway 구성을 비활성화하고 수동으로 설정.
 * application.yml: spring.flyway.enabled=false
 */
@Configuration
public class FlywayMultiTenantConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayMultiTenantConfig.class);

    /**
     * Master DB Flyway — 애플리케이션 시작 시 자동 실행.
     */
    @Bean(name = "masterFlyway")
    public Flyway masterFlyway(@Qualifier("masterDataSource") DataSource masterDataSource) {
        Flyway flyway = Flyway.configure()
            .dataSource(masterDataSource)
            .locations("classpath:db/migration/master")
            .table("flyway_schema_history_master")
            .baselineOnMigrate(true)
            .load();
        flyway.migrate();
        log.info("Master DB Flyway migration completed");
        return flyway;
    }

    /**
     * Master Flyway 초기화기 — 빈 로딩 순서 보장.
     */
    @Bean(name = "masterFlywayInitializer")
    public FlywayMigrationInitializer masterFlywayInitializer(@Qualifier("masterFlyway") Flyway masterFlyway) {
        return new FlywayMigrationInitializer(masterFlyway);
    }

    /**
     * 테넌트 DB용 Flyway 팩토리 메서드 (TenantOnboardingService에서 직접 호출).
     * 빈이 아닌 static 유틸로 제공.
     */
    public static void migrateTenantDatabase(DataSource tenantDataSource) {
        Flyway flyway = Flyway.configure()
            .dataSource(tenantDataSource)
            .locations("classpath:db/migration/tenant")
            .table("flyway_schema_history")
            .baselineOnMigrate(true)
            // CR-049: V23~V26 중복 해소 과정에서 V23.1/V24.1/V25.1/V26.1(소급 rename 된 과거 이력) +
            // V23.2/V23.3/V24.2/V24.3/V25.2/V25.3/V26.2/V26.3(통일된 신규 파일)이 혼재한다.
            // 기존 테넌트 DB 에서 V23.2 등이 V50 이후 시점에 실행될 수 있으므로 out-of-order 허용 필요.
            .outOfOrder(true)
            // CR-049: 예전에 제거된 resolve 불가 migration(V23.1 처럼 파일이 없어진 레거시 이력)은 무시.
            .ignoreMigrationPatterns("*:missing")
            .load();
        // CR-049: 본 CR 배포 중 V24.3 파일이 HNSW 가드 추가를 위해 한 번 변경되었다. 체크섬 불일치가 발생한 테넌트를
        // 자동 복구하기 위해 migrate 직전에 repair() 를 호출한다. repair 는 이미 일치하는 경우 no-op 이므로 안전.
        try {
            flyway.repair();
        } catch (Exception e) {
            log.warn("Tenant DB Flyway repair 실패 (무시하고 migrate 시도): {}", e.getMessage());
        }
        flyway.migrate();
        log.info("Tenant DB Flyway migration completed");
    }

    /**
     * App DB용 Flyway 마이그레이션 (AppOnboardingService에서 직접 호출).
     * App DB는 tenant 스키마를 재사용 — 동일 테이블 구조로 fallback 조회 가능.
     */
    public static void migrateAppDatabase(DataSource appDataSource) {
        Flyway flyway = Flyway.configure()
            .dataSource(appDataSource)
            .locations("classpath:db/migration/tenant")
            .table("flyway_schema_history")
            .baselineOnMigrate(true)
            // CR-049: tenant 경로와 동일한 이유로 out-of-order 허용 + 누락 파일 이력 무시.
            .outOfOrder(true)
            .ignoreMigrationPatterns("*:missing")
            .load();
        flyway.migrate();
        log.info("App DB Flyway migration completed");
    }
}
