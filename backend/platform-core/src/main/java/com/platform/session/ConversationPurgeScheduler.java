package com.platform.session;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CR-204 PRD-651 BIZ-295: 삭제 표시 뒤 보관 기간({@code chat.purge.retention-days})이 지난 대화와 되감긴 질문,
 * 삭제 표시된 그림과 문서 판을 테넌트마다 완전 삭제한다. 1시간마다, 기동 10분 뒤 처음.
 * 테넌트 테이블이라 {@code AttachmentGcScheduler} 처럼 캐시된 테넌트를 돌며 컨텍스트를 바꾼다(CR-122).
 */
@Component
public class ConversationPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConversationPurgeScheduler.class);

    private final ConversationPurgeService purgeService;
    private final TenantDataSourceManager tenantDataSourceManager;

    public ConversationPurgeScheduler(ConversationPurgeService purgeService,
                                      TenantDataSourceManager tenantDataSourceManager) {
        this.purgeService = purgeService;
        this.tenantDataSourceManager = tenantDataSourceManager;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void purgeExpired() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                ConversationPurgeService.ExpiredPurge r = purgeService.purgeExpiredForCurrentTenant();
                if (r.touchedAnything()) {
                    log.info("Conversation purge tenant={} sessions={} sessionsFailed={} messages={} images={} versions={} filesFailed={}",
                            tenantId, r.sessionsDeleted(), r.sessionsFailed(), r.messagesDeleted(),
                            r.imagesDeleted(), r.versionsDeleted(), r.filesFailed());
                }
            } catch (Exception e) {
                log.warn("Conversation purge failed for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
