package com.platform.policy;

import com.platform.domain.AuditLogEntity;
import com.platform.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final AuditLogRepository auditLogRepository;

    public AuditLogger(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Async
    public void log(String action, String target, String userId, String sessionId,
                     Map<String, Object> detail, String ipAddress) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setAction(action);
            entity.setTarget(target);
            entity.setUserId(userId);
            entity.setSessionId(sessionId);
            entity.setDetail(detail);
            entity.setIpAddress(ipAddress);
            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save audit log: {}", e.getMessage());
        }
    }

    @Async
    public void logLLMCall(String model, String sessionId, int inputTokens, int outputTokens) {
        log("llm_call", model, null, sessionId,
                Map.of("input_tokens", inputTokens, "output_tokens", outputTokens), null);
    }

    @Async
    public void logAction(String intent, String adapter, String sessionId, String status) {
        log("action_execute", adapter + ":" + intent, null, sessionId,
                Map.of("intent", intent, "adapter", adapter, "status", status), null);
    }
}
