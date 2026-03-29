package com.platform.tool.builtin;

import com.platform.action.AdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClaudeCodeTool 에러/서킷 이벤트 알림 서비스 (PRD-119).
 *
 * 규칙:
 * - NOTIFY 액션 에러: 즉시 알림 (재시도 없음)
 * - 서킷 OPEN 진입: 즉시 + 미복구 시 30분마다 재알림
 * - 중복 방지: 동일 에러 유형 5분 내 재알림 차단
 */
@Service
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class ClaudeCodeNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeNotificationService.class);

    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L;       // 5분
    private static final long CIRCUIT_RENOTIFY_MS = 30 * 60 * 1000L;  // 30분

    private final AdapterRegistry adapterRegistry;

    /** 에러 유형별 마지막 알림 시각 (중복 방지) */
    private final ConcurrentHashMap<String, Instant> lastNotifiedAt = new ConcurrentHashMap<>();

    public ClaudeCodeNotificationService(AdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    /** NOTIFY 액션 에러 발생 시 즉시 알림 */
    public void notifyError(ErrorClassification classification) {
        if (classification == null) return;

        String key = "error:" + classification.errorType();
        if (isDuplicate(key, DEDUP_WINDOW_MS)) {
            log.debug("중복 알림 억제: {} (5분 이내)", key);
            return;
        }

        String message = "[ClaudeCodeTool] 에러 감지 — 유형: %s, 패턴: %s"
                .formatted(classification.errorType(),
                        classification.pattern() != null ? classification.pattern() : "N/A");

        sendNotification("claude-code-error", message);
        lastNotifiedAt.put(key, Instant.now());
    }

    /** 서킷 OPEN 진입 시 알림 */
    public void notifyCircuitOpen(int consecutiveFailures) {
        String key = "circuit:OPEN";
        if (isDuplicate(key, CIRCUIT_RENOTIFY_MS)) {
            log.debug("서킷 OPEN 재알림 억제: 30분 이내");
            return;
        }

        String message = "[ClaudeCodeTool] 서킷 브레이커 OPEN — 연속 실패 %d회. 5분간 요청 차단됩니다."
                .formatted(consecutiveFailures);

        sendNotification("claude-code-circuit", message);
        lastNotifiedAt.put(key, Instant.now());
    }

    /** 서킷 복구 알림 */
    public void notifyCircuitRecovered() {
        lastNotifiedAt.remove("circuit:OPEN");

        String message = "[ClaudeCodeTool] 서킷 브레이커 복구 — 정상 동작 재개";
        sendNotification("claude-code-circuit", message);
    }

    private boolean isDuplicate(String key, long windowMs) {
        Instant last = lastNotifiedAt.get(key);
        return last != null && Instant.now().isBefore(last.plusMillis(windowMs));
    }

    private void sendNotification(String channel, String message) {
        Map<String, Object> event = Map.of("message", message, "source", "ClaudeCodeTool");
        // WebSocket 어댑터로 알림 (등록되어 있는 경우)
        trySend("websocket", channel, event);
        // Slack 어댑터로 알림 (등록되어 있는 경우)
        trySend("slack", channel, event);
        log.info("알림 전송: channel={}, message={}", channel, message);
    }

    private void trySend(String adapterId, String channel, Map<String, Object> event) {
        try {
            if (adapterRegistry.hasNotifyAdapter(adapterId)) {
                adapterRegistry.getNotifyAdapter(adapterId)
                        .publish(channel, event, Map.of());
            }
        } catch (Exception e) {
            log.warn("알림 전송 실패 ({}): {}", adapterId, e.getMessage());
        }
    }
}
