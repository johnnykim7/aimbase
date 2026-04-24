package com.platform.tenant.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * CR-066: 애플리케이션 기동 완료 시 활성 테넌트 DB 에 Flyway 를 자동 재실행하는 리스너.
 *
 * <p>기본값 비활성(false). 운영 환경에서 명시적으로 {@code platform.tenant.migration.auto-migrate-on-startup=true}
 * 로 켤 때만 동작한다. 켜져 있어도 개별 테넌트 실패는 {@link TenantMigrationRunner} 가 격리하므로
 * 기동을 차단하지 않는다.
 *
 * <p>Admin API {@code POST /api/v1/platform/tenants/migrate} 가 주(主) 수단이고, 본 리스너는
 * 배포 자동화를 위한 안전망으로 사용한다.
 */
@Component
public class TenantMigrationStartupRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationStartupRunner.class);

    private final TenantMigrationRunner migrationRunner;

    @Value("${platform.tenant.migration.auto-migrate-on-startup:false}")
    private boolean autoMigrateOnStartup;

    public TenantMigrationStartupRunner(TenantMigrationRunner migrationRunner) {
        this.migrationRunner = migrationRunner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!autoMigrateOnStartup) {
            log.info("CR-066 startup auto-migrate disabled (platform.tenant.migration.auto-migrate-on-startup=false)");
            return;
        }

        log.info("CR-066 startup auto-migrate enabled — running migrateAll()");
        try {
            TenantMigrationResult result = migrationRunner.migrateAll(false);
            log.info("CR-066 startup auto-migrate done — total={}, success={}, failed={}, skipped={}",
                    result.total(), result.success(), result.failed(), result.skipped());
            if (result.failed() > 0) {
                // 실패 격리: 로그만 남기고 기동은 계속 진행
                log.warn("CR-066 startup auto-migrate: {} tenant(s) failed but startup continues. "
                        + "Review Admin API POST /api/v1/platform/tenants/migrate for retry.", result.failed());
            }
        } catch (Exception e) {
            // 최상위 예외도 격리 — 기동 실패로 전파되지 않도록 함
            log.error("CR-066 startup auto-migrate aborted due to unexpected error (startup continues): {}",
                    e.getMessage(), e);
        }
    }
}
