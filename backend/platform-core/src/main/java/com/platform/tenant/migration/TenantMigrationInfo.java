package com.platform.tenant.migration;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CR-066: 단일 테넌트의 Flyway 마이그레이션 현황.
 *
 * <p>{@code flyway_schema_history} 테이블을 조회하여 적용 버전 이력을 돌려준다.
 */
public record TenantMigrationInfo(
        String tenantId,
        String dbName,
        String currentVersion,
        List<AppliedMigration> applied,
        List<String> pendingVersions
) {

    public record AppliedMigration(
            int installedRank,
            String version,
            String description,
            String script,
            boolean success,
            OffsetDateTime installedAt,
            int executionTimeMs
    ) {}
}
