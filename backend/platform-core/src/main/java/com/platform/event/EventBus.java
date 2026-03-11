package com.platform.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 이벤트 버스 (MVP: Redis Streams).
 * Production: Kafka로 교체
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private static final String STREAM_KEY_PREFIX = "events:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public EventBus(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String topic, Map<String, Object> payload) {
        try {
            String streamKey = STREAM_KEY_PREFIX + topic;
            Map<String, String> entry = Map.of(
                    "payload", objectMapper.writeValueAsString(payload),
                    "timestamp", String.valueOf(System.currentTimeMillis())
            );
            redisTemplate.opsForStream().add(streamKey, entry);
            log.debug("Published event to {}: {}", topic, payload);
        } catch (Exception e) {
            log.warn("Failed to publish event to {}: {}", topic, e.getMessage());
        }
    }

    public void publishActionExecuted(String intent, String adapter, String status, String sessionId) {
        publish("action.executed", Map.of(
                "intent", intent,
                "adapter", adapter,
                "status", status,
                "session_id", sessionId != null ? sessionId : ""
        ));
    }

    public void publishLLMCalled(String model, int inputTokens, int outputTokens) {
        publish("llm.called", Map.of(
                "model", model,
                "input_tokens", inputTokens,
                "output_tokens", outputTokens
        ));
    }
}
