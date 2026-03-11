package com.platform.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.UnifiedMessage;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final String SESSION_PREFIX = "session:messages:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<UnifiedMessage> getMessages(String sessionId) {
        try {
            String key = buildKey(sessionId);
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to load session {}: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void saveMessages(String sessionId, List<UnifiedMessage> messages) {
        try {
            String key = buildKey(sessionId);
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (Exception e) {
            log.warn("Failed to save session {}: {}", sessionId, e.getMessage());
        }
    }

    public void appendMessage(String sessionId, UnifiedMessage message) {
        List<UnifiedMessage> messages = getMessages(sessionId);
        messages.add(message);
        saveMessages(sessionId, messages);
    }

    public void clearSession(String sessionId) {
        redisTemplate.delete(buildKey(sessionId));
    }

    public boolean hasSession(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(sessionId)));
    }

    /**
     * 테넌트별 Redis key 생성.
     * 테넌트 컨텍스트가 있으면: tenant:{tenantId}:session:messages:{sessionId}
     * 없으면 (단일 모드): session:messages:{sessionId}
     */
    private String buildKey(String sessionId) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return "tenant:" + tenantId + ":" + SESSION_PREFIX + sessionId;
        }
        return SESSION_PREFIX + sessionId;
    }
}
