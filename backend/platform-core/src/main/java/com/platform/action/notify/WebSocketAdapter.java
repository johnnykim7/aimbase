package com.platform.action.notify;

import com.platform.action.model.HealthStatus;
import com.platform.action.model.NotifyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class WebSocketAdapter implements NotifyAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAdapter.class);

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public String getId() {
        return "websocket";
    }

    @Override
    public void connect(Map<String, Object> config) {
        // Spring WebSocket은 자동 관리
    }

    @Override
    public void disconnect() {}

    @Override
    public HealthStatus healthCheck() {
        // WebSocket 서버가 실행 중이면 healthy
        return HealthStatus.healthy(0);
    }

    @Override
    public NotifyResult publish(String channel, Object event, Map<String, Object> options) {
        try {
            String messageId = UUID.randomUUID().toString();
            String destination = channel.startsWith("/topic/") ? channel : "/topic/" + channel;
            messagingTemplate.convertAndSend(destination, event);
            log.debug("WebSocket published to {} : {}", destination, event);
            return NotifyResult.success(messageId);
        } catch (Exception e) {
            log.error("WebSocket publish error to {}: {}", channel, e.getMessage());
            return NotifyResult.failure(e.getMessage());
        }
    }
}
