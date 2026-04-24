package com.platform.tenant.migration;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CR-066: 테넌트 일괄 Flyway 마이그레이션 결과.
 *
 * <p>1개 테넌트 실패 시에도 나머지는 진행하며, 각 테넌트별 결과를 {@code details} 에 담는다.
 */
public record TenantMigrationResult(
        int total,
        int success,
        int failed,
        int skipped,
        List<Detail> details,
        OffsetDateTime executedAt
) {

    public enum Status { SUCCESS, FAILED, SKIPPED }

    public record Detail(
            String tenantId,
            String dbName,
            Status status,
            /** 적용된 마이그레이션 수 (SUCCESS 시). Flyway.migrate() 반환값 기반 */
            int migrationsApplied,
            /** 실패 사유 (FAILED 시) */
            String error
    ) {}
}
