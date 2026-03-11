package com.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;

/**
 * 현재 ThreadLocal의 tenant_id를 기준으로 DataSource를 동적으로 선택.
 *
 * determineTargetDataSource()를 오버라이드하여 TenantDataSourceManager를 직접 조회.
 * 이 방식은 afterPropertiesSet() 이후에 동적으로 추가된 테넌트 DataSource도
 * 즉시 라우팅할 수 있음 (AbstractRoutingDataSource의 내부 캐시 우회).
 *
 * - tenant_id 있음 → TenantDataSourceManager에서 해당 테넌트 DataSource 반환
 * - tenant_id 없음 → Master DataSource (슈퍼어드민 API, 플랫폼 경로)
 */
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantRoutingDataSource.class);

    private TenantDataSourceManager dataSourceManager;
    private DataSource masterDataSource;

    /**
     * TenantDataSourceConfig에서 생성 후 호출하여 의존성 주입.
     */
    public void configure(TenantDataSourceManager manager, DataSource master) {
        this.dataSourceManager = manager;
        this.masterDataSource = master;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getTenantId();
    }

    /**
     * AbstractRoutingDataSource의 내부 resolvedDataSources 캐시 대신
     * TenantDataSourceManager를 직접 조회하여 동적 DataSource 등록을 지원.
     */
    @Override
    protected DataSource determineTargetDataSource() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return masterDataSource;
        }
        DataSource ds = dataSourceManager.getTenantDataSource(tenantId);
        if (ds == null) {
            log.error("No DataSource registered for tenant: {}", tenantId);
            throw new IllegalStateException("Unknown tenant: " + tenantId);
        }
        return ds;
    }
}
