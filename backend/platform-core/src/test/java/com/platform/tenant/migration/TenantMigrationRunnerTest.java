package com.platform.tenant.migration;

import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.TenantRepository;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * CR-066 TenantMigrationRunner 단위 테스트.
 *
 * <p>Flyway 실제 실행 없이 "테넌트 순회 + 필터링 + 실패 격리" 로직만 검증한다.
 * Flyway migrate 실제 동작은 통합 테스트(별도)에서 커버.
 */
@ExtendWith(MockitoExtension.class)
class TenantMigrationRunnerTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private TenantDataSourceManager dataSourceManager;
    @Mock private DataSource dataSource;

    private TenantMigrationRunner runner;

    @BeforeEach
    void setUp() {
        runner = new TenantMigrationRunner(tenantRepository, dataSourceManager);
    }

    @Test
    void migrate_skips_when_tenantId_is_blank() {
        TenantMigrationResult result = runner.migrate(List.of("", "   "), false);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.success()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.details())
                .extracting(TenantMigrationResult.Detail::status)
                .containsExactly(TenantMigrationResult.Status.SKIPPED, TenantMigrationResult.Status.SKIPPED);
        assertThat(result.details().get(0).error()).contains("null or blank");
    }

    @Test
    void migrate_skips_when_tenant_not_found() {
        when(tenantRepository.findById("missing")).thenReturn(Optional.empty());

        TenantMigrationResult result = runner.migrate(List.of("missing"), false);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.details().get(0).status()).isEqualTo(TenantMigrationResult.Status.SKIPPED);
        assertThat(result.details().get(0).error()).contains("not found");
    }

    @Test
    void migrate_skips_when_tenant_not_active() {
        TenantEntity suspended = newTenant("t1", "aimbase_t1", "suspended");
        when(tenantRepository.findById("t1")).thenReturn(Optional.of(suspended));

        TenantMigrationResult result = runner.migrate(List.of("t1"), false);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.details().get(0).error()).contains("status=suspended");
    }

    @Test
    void migrate_skips_when_dataSource_not_cached() {
        TenantEntity active = newTenant("t1", "aimbase_t1", "active");
        when(tenantRepository.findById("t1")).thenReturn(Optional.of(active));
        when(dataSourceManager.getTenantDataSource("t1")).thenReturn(null);

        TenantMigrationResult result = runner.migrate(List.of("t1"), false);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.details().get(0).status()).isEqualTo(TenantMigrationResult.Status.SKIPPED);
        assertThat(result.details().get(0).error()).contains("dataSource not available");
    }

    @Test
    void migrate_isolates_failures_and_continues_with_rest() {
        // t1: 정상 경로 (runMigration 이 Flyway 호출 시 유효하지 않은 DataSource 로 실패 → FAILED)
        // t2: SKIPPED (dataSource 캐시 없음)
        // t3: SKIPPED (비활성 상태)
        // → 하나의 FAILED 가 나머지 진행을 막지 않는지 검증
        TenantEntity t1 = newTenant("t1", "aimbase_t1", "active");
        TenantEntity t2 = newTenant("t2", "aimbase_t2", "active");
        TenantEntity t3 = newTenant("t3", "aimbase_t3", "suspended");
        when(tenantRepository.findById("t1")).thenReturn(Optional.of(t1));
        when(tenantRepository.findById("t2")).thenReturn(Optional.of(t2));
        when(tenantRepository.findById("t3")).thenReturn(Optional.of(t3));
        when(dataSourceManager.getTenantDataSource("t1")).thenReturn(dataSource);
        when(dataSourceManager.getTenantDataSource("t2")).thenReturn(null);
        // t3 는 status 체크에서 먼저 걸러지므로 dataSourceManager 호출되지 않음

        TenantMigrationResult result = runner.migrate(List.of("t1", "t2", "t3"), false);

        assertThat(result.total()).isEqualTo(3);
        // t1: Flyway.configure().dataSource(mockDs).load() 는 실제 JDBC 커넥션 시도 시 실패 → FAILED
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.success()).isZero();

        // 실패한 t1 의 error 메시지가 details 에 기록되었는지
        TenantMigrationResult.Detail t1Detail = result.details().stream()
                .filter(d -> "t1".equals(d.tenantId())).findFirst().orElseThrow();
        assertThat(t1Detail.status()).isEqualTo(TenantMigrationResult.Status.FAILED);
        assertThat(t1Detail.error()).isNotBlank();
    }

    @Test
    void migrateAll_queries_active_tenants_from_repository() {
        TenantEntity t1 = newTenant("t1", "aimbase_t1", "active");
        TenantEntity t2 = newTenant("t2", "aimbase_t2", "active");
        when(tenantRepository.findByStatus("active")).thenReturn(List.of(t1, t2));
        when(tenantRepository.findById("t1")).thenReturn(Optional.of(t1));
        when(tenantRepository.findById("t2")).thenReturn(Optional.of(t2));
        when(dataSourceManager.getTenantDataSource("t1")).thenReturn(null);
        when(dataSourceManager.getTenantDataSource("t2")).thenReturn(null);

        TenantMigrationResult result = runner.migrateAll(false);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);  // 둘 다 dataSource 캐시 없음 → SKIPPED
    }

    @Test
    void describe_throws_when_tenantId_blank() {
        assertThatThrownBy(() -> runner.describe(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    void describe_throws_when_tenant_not_found() {
        when(tenantRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner.describe("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant not found");
    }

    @Test
    void describe_throws_when_dataSource_not_available() {
        TenantEntity active = newTenant("t1", "aimbase_t1", "active");
        when(tenantRepository.findById("t1")).thenReturn(Optional.of(active));
        when(dataSourceManager.getTenantDataSource("t1")).thenReturn(null);

        assertThatThrownBy(() -> runner.describe("t1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataSource not available");
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private TenantEntity newTenant(String id, String dbName, String status) {
        TenantEntity t = new TenantEntity();
        t.setId(id);
        t.setName(id);
        t.setStatus(status);
        t.setDbHost("localhost");
        t.setDbPort(5432);
        t.setDbName(dbName);
        t.setDbUsername("platform");
        t.setDbPasswordEncrypted("encrypted");
        return t;
    }
}
