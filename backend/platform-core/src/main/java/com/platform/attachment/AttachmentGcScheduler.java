package com.platform.attachment;

import com.platform.domain.ChatAttachmentEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CR-061 BIZ-101 — 만료된 첨부를 주기 GC.
 * 매 5분 최대 100건 배치. 스토리지 → DB 순 삭제.
 *
 * <p>CR-122: {@code chat_attachments} 는 테넌트 테이블이므로 테넌트별로 순회해야 한다.
 * 이전엔 {@link TenantContext} 설정 없이 조회해 master DB 로 라우팅됐고,
 * master 엔 해당 테이블이 없어 5분마다 {@code relation "chat_attachments" does not exist}
 * 로 실패하며 GC 가 전혀 동작하지 않았다(운영 로그 실측).</p>
 */
@Component
public class AttachmentGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttachmentGcScheduler.class);

    private final ChatAttachmentRepository repo;
    private final StorageService storage;
    private final TenantDataSourceManager tenantDataSourceManager;

    public AttachmentGcScheduler(ChatAttachmentRepository repo, StorageService storage,
                                  TenantDataSourceManager tenantDataSourceManager) {
        this.repo = repo;
        this.storage = storage;
        this.tenantDataSourceManager = tenantDataSourceManager;
    }

    /** CR-122: 캐시된 테넌트 DataSource 를 순회하며 각 테넌트 컨텍스트에서 GC 수행. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void purgeExpired() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                purgeExpiredForCurrentTenant();
            } catch (Exception e) {
                log.warn("Attachment GC failed for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void purgeExpiredForCurrentTenant() {
        List<ChatAttachmentEntity> expired = repo.findTop100ByExpiresAtBefore(OffsetDateTime.now());
        if (expired.isEmpty()) return;

        int ok = 0;
        int storageFailed = 0;
        for (ChatAttachmentEntity e : expired) {
            try {
                storage.delete(e.getStoragePath());
            } catch (Exception ex) {
                storageFailed++;
                log.warn("Attachment GC — storage delete failed id={} path={}",
                        e.getId(), e.getStoragePath(), ex);
            }
            try {
                repo.deleteById(e.getId());
                ok++;
            } catch (Exception ex) {
                log.error("Attachment GC — DB delete failed id={}", e.getId(), ex);
            }
        }
        log.info("Attachment GC — tenant={} purged={} storageFailed={} total={}",
                TenantContext.getTenantId(), ok, storageFailed, expired.size());
    }
}
