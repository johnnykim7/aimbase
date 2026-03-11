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
            .load();
        flyway.migrate();
        log.info("Tenant DB Flyway migration completed");
    }
}
