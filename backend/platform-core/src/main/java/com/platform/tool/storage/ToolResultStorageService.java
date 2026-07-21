package com.platform.tool.storage;

import com.platform.domain.ToolResultStorageEntity;
import com.platform.repository.ToolResultStorageRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-048 PRD-301: 대형 tool result 외부 저장 서비스.
 * 저장/조회 + session_id 권한 검증 + 일일 만료 스케줄러.
 */
@Service
public class ToolResultStorageService {

    private static final Logger log = LoggerFactory.getLogger(ToolResultStorageService.class);

    /** 세션 TTL과 일치 (BIZ-002) */
    private static final long TTL_HOURS = 24;

    private final ToolResultStorageRepository repository;
    /** CR-122: 만료 스케줄러의 테넌트 순회용. */
    private final TenantDataSourceManager tenantDataSourceManager;
    private final TransactionTemplate transactionTemplate;

    public ToolResultStorageService(ToolResultStorageRepository repository,
                                     TenantDataSourceManager tenantDataSourceManager,
                                     TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.tenantDataSourceManager = tenantDataSourceManager;
        this.transactionTemplate = transactionTemplate;
    }

    /** 원본 content를 저장하고 result_id 반환. */
    @Transactional
    public String store(String sessionId, String toolName, String fullContent, String summary) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        String resultId = "res_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        OffsetDateTime now = OffsetDateTime.now();
        int sizeBytes = fullContent == null ? 0 : fullContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        ToolResultStorageEntity entity = new ToolResultStorageEntity(
                resultId,
                sessionId,
                toolName != null ? toolName : "unknown",
                fullContent != null ? fullContent : "",
                summary,
                sizeBytes,
                now,
                now.plusHours(TTL_HOURS)
        );
        repository.save(entity);
        log.debug("Stored tool result: id={} session={} tool={} size={}B", resultId, sessionId, toolName, sizeBytes);
        return resultId;
    }

    /** session_id 일치를 강제하며 원본을 조회. */
    @Transactional(readOnly = true)
    public ReadResult read(String sessionId, String resultId) {
        if (sessionId == null || resultId == null) {
            return ReadResult.notFound();
        }
        Optional<ToolResultStorageEntity> opt = repository.findById(resultId);
        if (opt.isEmpty()) return ReadResult.notFound();

        ToolResultStorageEntity entity = opt.get();
        if (!sessionId.equals(entity.getSessionId())) {
            log.warn("Forbidden tool result access: requester_session={} owner_session={} result_id={}",
                    sessionId, entity.getSessionId(), resultId);
            return ReadResult.forbidden();
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return ReadResult.expired();
        }
        return ReadResult.ok(entity);
    }

    /**
     * 매일 03:00에 만료 레코드 삭제.
     *
     * <p>CR-122: {@code tool_result_storage} 는 테넌트 테이블이므로 테넌트별로 순회한다.
     * 이전엔 {@link TenantContext} 없이 조회해 master DB 로 라우팅됐고, master 엔 해당 테이블이
     * 없어 매일 조용히 실패(catch 로 삼킴)하며 만료 레코드가 전혀 정리되지 않았다.</p>
     *
     * <p>순회 메서드에는 {@code @Transactional} 을 걸지 않는다 — 트랜잭션이 첫 테넌트 커넥션에
     * 묶이면 이후 테넌트 라우팅이 어긋난다. 테넌트별 삭제만 {@link TransactionTemplate} 으로 감싼다.</p>
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void expireOldResults() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                Integer removed = transactionTemplate.execute(
                        status -> repository.deleteExpired(OffsetDateTime.now()));
                if (removed != null && removed > 0) {
                    log.info("Expired and removed {} tool_result_storage rows (tenant={})",
                            removed, tenantId);
                }
            } catch (Exception e) {
                log.warn("Failed to expire tool_result_storage for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** ReadToolResult 결과 래퍼. */
    public record ReadResult(Status status, ToolResultStorageEntity entity) {
        public enum Status { OK, NOT_FOUND, FORBIDDEN, EXPIRED }
        public static ReadResult ok(ToolResultStorageEntity e) { return new ReadResult(Status.OK, e); }
        public static ReadResult notFound() { return new ReadResult(Status.NOT_FOUND, null); }
        public static ReadResult forbidden() { return new ReadResult(Status.FORBIDDEN, null); }
        public static ReadResult expired() { return new ReadResult(Status.EXPIRED, null); }
    }
}
