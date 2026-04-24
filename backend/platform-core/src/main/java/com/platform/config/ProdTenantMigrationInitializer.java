package com.platform.config;

import com.platform.tenant.TenantDataSourceManager;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CR-049: prod 환경에서 앱 기동 시 모든 활성 테넌트 DB 에 Flyway 마이그레이션을 자동 실행.
 *
 * 기존에는 TenantOnboardingService 가 신규 테넌트 생성 때만 migrate 를 호출하고,
 * 기존 테넌트는 재배포 후에도 새 V?? 파일이 적용되지 않는 누락이 있었다
 * (CR-046/048/055/058 의 V51~V54 가 운영 DB 에 반영되지 않았던 사례). 본 Initializer 가
 * 부팅마다 모든 활성 테넌트에 대해 migrate 를 멱등 호출하여 이 문제를 구조적으로 해소한다.
 *
 * - {@code @Profile("prod")} — 로컬/테스트 프로파일은 LocalDevInitializer 가 담당.
 * - {@code @Order} 로 TenantDataSourceManager 초기화(AbstractRoutingDataSource target 로딩)
 *   이후에 실행되도록 후순위를 부여.
 * - 실패한 테넌트는 로그만 남기고 다음 테넌트로 계속 진행 (한 테넌트 실패가 서비스 기동을 막지 않는다).
 */
@Profile("prod")
@Component
@Order(1000)
public class ProdTenantMigrationInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdTenantMigrationInitializer.class);

    private final TenantDataSourceManager dataSourceManager;

    public ProdTenantMigrationInitializer(TenantDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, HikariDataSource> cached = dataSourceManager.getAllCachedDataSources();
        if (cached.isEmpty()) {
            log.info("[ProdTenantMigration] no tenant datasources cached — skip");
            return;
        }
        log.info("[ProdTenantMigration] 기존 테넌트 {}개에 Flyway 마이그레이션 자동 적용 시작", cached.size());

        int ok = 0;
        int fail = 0;
        for (Map.Entry<String, HikariDataSource> e : cached.entrySet()) {
            String tenantId = e.getKey();
            HikariDataSource ds = e.getValue();
            try {
                FlywayMultiTenantConfig.migrateTenantDatabase(ds);
                ok++;
                log.info("[ProdTenantMigration] tenant={} migrate OK", tenantId);
            } catch (Exception ex) {
                fail++;
                log.error("[ProdTenantMigration] tenant={} migrate FAILED: {}", tenantId, ex.getMessage(), ex);
            }
        }
        log.info("[ProdTenantMigration] 완료 — 성공 {} / 실패 {}", ok, fail);
    }
}
