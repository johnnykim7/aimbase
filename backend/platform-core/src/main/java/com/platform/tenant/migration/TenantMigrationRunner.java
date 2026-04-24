package com.platform.tenant.migration;

import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.TenantRepository;
import com.platform.tenant.TenantDataSourceManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CR-066: 활성 테넌트 DB 의 Flyway 마이그레이션을 일괄/단일 실행하는 오케스트레이터.
 *
 * <p>기존 {@code FlywayMultiTenantConfig#migrateTenantDatabase(DataSource)} 가
 * {@code baselineOnMigrate(true)} + {@code outOfOrder(true)} +
 * {@code ignoreMigrationPatterns("*:missing")} + {@code repair()} 를 이미 포함하므로
 * 본 Runner 는 "활성 테넌트 순회 + 실패 격리 + 결과 집계" 에만 책임진다.
 *
 * <p>실패 격리: 1개 테넌트 실패 시 나머지 진행, 결과 {@link TenantMigrationResult} 로 요약 반환.
 */
@Component
public class TenantMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationRunner.class);

    private final TenantRepository tenantRepository;
    private final TenantDataSourceManager dataSourceManager;

    public TenantMigrationRunner(TenantRepository tenantRepository,
                                  TenantDataSourceManager dataSourceManager) {
        this.tenantRepository = tenantRepository;
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * 활성 상태("active") 테넌트 전체 일괄 migrate.
     */
    public TenantMigrationResult migrateAll(boolean dryRun) {
        List<TenantEntity> tenants = tenantRepository.findByStatus("active");
        List<String> tenantIds = tenants.stream().map(TenantEntity::getId).toList();
        return migrate(tenantIds, dryRun);
    }

    /**
     * 지정된 테넌트 ID 목록에 대해 일괄 migrate.
     * 비활성/존재하지 않는 테넌트는 {@code SKIPPED} 로 기록하고 계속 진행.
     */
    public TenantMigrationResult migrate(List<String> tenantIds, boolean dryRun) {
        log.info("CR-066 tenant migration start — count={}, dryRun={}", tenantIds.size(), dryRun);

        List<TenantMigrationResult.Detail> details = new ArrayList<>();
        int success = 0, failed = 0, skipped = 0;

        for (String tenantId : tenantIds) {
            if (tenantId == null || tenantId.isBlank()) {
                details.add(new TenantMigrationResult.Detail(
                        "(null)", null, TenantMigrationResult.Status.SKIPPED, 0,
                        "tenantId is null or blank"));
                skipped++;
                continue;
            }
            TenantEntity tenant = tenantRepository.findById(tenantId).orElse(null);
            if (tenant == null) {
                log.warn("CR-066 tenant not found: {}", tenantId);
                details.add(new TenantMigrationResult.Detail(
                        tenantId, null, TenantMigrationResult.Status.SKIPPED, 0,
                        "tenant not found in master DB"));
                skipped++;
                continue;
            }
            if (!"active".equals(tenant.getStatus())) {
                log.info("CR-066 tenant not active, skip: {} (status={})", tenantId, tenant.getStatus());
                details.add(new TenantMigrationResult.Detail(
                        tenantId, tenant.getDbName(), TenantMigrationResult.Status.SKIPPED, 0,
                        "tenant status=" + tenant.getStatus()));
                skipped++;
                continue;
            }

            DataSource ds = dataSourceManager.getTenantDataSource(tenantId);
            if (ds == null) {
                log.warn("CR-066 tenant dataSource not cached, skip: {}", tenantId);
                details.add(new TenantMigrationResult.Detail(
                        tenantId, tenant.getDbName(), TenantMigrationResult.Status.SKIPPED, 0,
                        "dataSource not available (tenant may need reload)"));
                skipped++;
                continue;
            }

            try {
                int applied = runMigration(ds, tenant.getDbName(), dryRun);
                details.add(new TenantMigrationResult.Detail(
                        tenantId, tenant.getDbName(), TenantMigrationResult.Status.SUCCESS, applied, null));
                success++;
                log.info("CR-066 tenant migration success: tenant={}, db={}, applied={}, dryRun={}",
                        tenantId, tenant.getDbName(), applied, dryRun);
            } catch (Exception e) {
                // 실패 격리 — 개별 실패는 기록만 하고 다음 테넌트로 진행
                log.error("CR-066 tenant migration failed: tenant={}, db={}, error={}",
                        tenantId, tenant.getDbName(), e.getMessage(), e);
                details.add(new TenantMigrationResult.Detail(
                        tenantId, tenant.getDbName(), TenantMigrationResult.Status.FAILED, 0,
                        e.getMessage()));
                failed++;
            }
        }

        TenantMigrationResult result = new TenantMigrationResult(
                tenantIds.size(), success, failed, skipped, details, OffsetDateTime.now());
        log.info("CR-066 tenant migration done — total={}, success={}, failed={}, skipped={}",
                result.total(), result.success(), result.failed(), result.skipped());
        return result;
    }

    /**
     * 단일 테넌트의 마이그레이션 현황 조회 (적용 이력 + 대기 중 버전).
     */
    public TenantMigrationInfo describe(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is null or blank");
        }
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found: " + tenantId));
        DataSource ds = dataSourceManager.getTenantDataSource(tenantId);
        if (ds == null) {
            throw new IllegalStateException("tenant dataSource not available: " + tenantId);
        }

        Flyway flyway = buildFlyway(ds);
        org.flywaydb.core.api.MigrationInfoService infoService = flyway.info();

        List<TenantMigrationInfo.AppliedMigration> applied = new ArrayList<>();
        for (MigrationInfo info : infoService.applied()) {
            applied.add(new TenantMigrationInfo.AppliedMigration(
                    info.getInstalledRank(),
                    info.getVersion() != null ? info.getVersion().getVersion() : null,
                    info.getDescription(),
                    info.getScript(),
                    info.getState() != null && info.getState().isApplied(),
                    info.getInstalledOn() != null
                            ? info.getInstalledOn().toInstant().atOffset(OffsetDateTime.now().getOffset())
                            : null,
                    info.getExecutionTime()
            ));
        }
        applied.sort(Comparator.comparingInt(TenantMigrationInfo.AppliedMigration::installedRank));

        List<String> pendingVersions = new ArrayList<>();
        for (MigrationInfo info : infoService.pending()) {
            if (info.getVersion() != null) {
                pendingVersions.add(info.getVersion().getVersion());
            }
        }

        MigrationInfo current = infoService.current();
        String currentVersion = (current != null && current.getVersion() != null)
                ? current.getVersion().getVersion() : null;

        return new TenantMigrationInfo(tenantId, tenant.getDbName(), currentVersion, applied, pendingVersions);
    }

    /**
     * {@link com.platform.config.FlywayMultiTenantConfig#migrateTenantDatabase(DataSource)} 와
     * 동일한 옵션을 유지하되, dryRun 인 경우 {@code info().pending()} 개수만 계산하여 반환한다.
     *
     * @return 적용된 마이그레이션 수 (dryRun 이면 pending 개수)
     */
    private int runMigration(DataSource ds, String dbName, boolean dryRun) {
        Flyway flyway = buildFlyway(ds);

        if (dryRun) {
            int pending = flyway.info().pending().length;
            log.info("CR-066 dry-run: db={}, pending={}", dbName, pending);
            return pending;
        }

        try {
            flyway.repair();
        } catch (Exception e) {
            log.warn("CR-066 Flyway repair 실패 (무시하고 migrate 시도): db={}, error={}", dbName, e.getMessage());
        }
        MigrateResult migrateResult = flyway.migrate();
        return migrateResult != null ? migrateResult.migrationsExecuted : 0;
    }

    private Flyway buildFlyway(DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/tenant")
                .table("flyway_schema_history")
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .ignoreMigrationPatterns("*:missing")
                .load();
    }
}
