package com.platform.tool.storage;

import com.platform.domain.ToolResultStorageEntity;
import com.platform.repository.ToolResultStorageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ToolResultStorageService(ToolResultStorageRepository repository) {
        this.repository = repository;
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

    /** 매일 03:00에 만료 레코드 삭제. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void expireOldResults() {
        try {
            int removed = repository.deleteExpired(OffsetDateTime.now());
            if (removed > 0) {
                log.info("Expired and removed {} tool_result_storage rows", removed);
            }
        } catch (Exception e) {
            log.warn("Failed to expire tool_result_storage: {}", e.getMessage());
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
