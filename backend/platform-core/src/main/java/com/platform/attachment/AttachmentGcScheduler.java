package com.platform.attachment;

import com.platform.domain.ChatAttachmentEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CR-061 BIZ-101 — 만료된 첨부를 주기 GC.
 * 매 5분 최대 100건 배치. 스토리지 → DB 순 삭제.
 */
@Component
public class AttachmentGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttachmentGcScheduler.class);

    private final ChatAttachmentRepository repo;
    private final StorageService storage;

    public AttachmentGcScheduler(ChatAttachmentRepository repo, StorageService storage) {
        this.repo = repo;
        this.storage = storage;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void purgeExpired() {
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
        log.info("Attachment GC — purged={} storageFailed={} total={}",
                ok, storageFailed, expired.size());
    }
}
