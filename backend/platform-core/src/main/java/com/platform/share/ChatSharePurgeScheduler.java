package com.platform.share;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기한 지난 공유 사본을 매 1시간 지운다(CR-182 3.5.4). 테넌트 DB 마다 돈다. */
@Component
public class ChatSharePurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatSharePurgeScheduler.class);

    private final ChatShareService shareService;
    private final TenantDataSourceManager tenantDataSourceManager;

    public ChatSharePurgeScheduler(ChatShareService shareService, TenantDataSourceManager tenantDataSourceManager) {
        this.shareService = shareService;
        this.tenantDataSourceManager = tenantDataSourceManager;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void purge() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                int purged = shareService.purgeExpired();
                if (purged > 0) log.info("Share snapshots purged tenant={} count={}", tenantId, purged);
            } catch (Exception e) {
                log.warn("Share purge failed tenant={}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
