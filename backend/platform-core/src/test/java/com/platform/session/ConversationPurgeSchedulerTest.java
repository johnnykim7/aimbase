package com.platform.session;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-204 TC-CHT-166: 테넌트마다 한 번씩, 한 테넌트가 실패해도 다음 테넌트로. */
@ExtendWith(MockitoExtension.class)
class ConversationPurgeSchedulerTest {

    @Mock ConversationPurgeService purgeService;
    @Mock TenantDataSourceManager tenantDataSourceManager;

    @Test
    void runsOncePerTenantWithContextAndClearsIt() {
        Map<String, HikariDataSource> tenants = new LinkedHashMap<>();
        tenants.put("t1", mock(HikariDataSource.class));
        tenants.put("t2", mock(HikariDataSource.class));
        when(tenantDataSourceManager.getAllCachedDataSources()).thenReturn(tenants);
        List<String> seen = new ArrayList<>();
        when(purgeService.purgeExpiredForCurrentTenant()).thenAnswer(inv -> {
            seen.add(TenantContext.getTenantId());
            if (seen.size() == 1) throw new IllegalStateException("db down");
            return new ConversationPurgeService.ExpiredPurge(1, 0, 0, 0, 0, 0);
        });

        new ConversationPurgeScheduler(purgeService, tenantDataSourceManager).purgeExpired();

        assertThat(seen).containsExactly("t1", "t2");
        verify(purgeService, times(2)).purgeExpiredForCurrentTenant();
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
