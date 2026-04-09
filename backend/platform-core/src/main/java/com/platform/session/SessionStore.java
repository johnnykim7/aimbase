package com.platform.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final String SESSION_PREFIX = "session:messages:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final TransactionTemplate transactionTemplate;
    private final com.platform.config.PlatformSettingsService platformSettings;

    public SessionStore(RedisTemplate<String, String> redisTemplate,
                        ObjectMapper objectMapper,
                        ConversationSessionRepository sessionRepository,
                        ConversationMessageRepository messageRepository,
                        TransactionTemplate transactionTemplate,
                        com.platform.config.PlatformSettingsService platformSettings) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.transactionTemplate = transactionTemplate;
        this.platformSettings = platformSettings;
    }

    private Duration getSessionTtl() {
        int hours = platformSettings.getInt("session.session-ttl-hours", 24);
        return Duration.ofHours(hours);
    }

    public List<UnifiedMessage> getMessages(String sessionId) {
        try {
            String key = buildKey(sessionId);
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<>() {});
            }
            // Redis miss — fallback to DB
            return loadFromDb(sessionId);
        } catch (Exception e) {
            log.warn("Failed to load session {} from Redis, trying DB: {}", sessionId, e.getMessage());
            return loadFromDb(sessionId);
        }
    }

    public void saveMessages(String sessionId, List<UnifiedMessage> messages) {
        try {
            String key = buildKey(sessionId);
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, json, getSessionTtl());
        } catch (Exception e) {
            log.warn("Failed to save session {} to Redis: {}", sessionId, e.getMessage());
        }

        // Dual-write: persist to DB asynchronously via Virtual Thread
        Thread.ofVirtual().start(() -> {
            try {
                persistToDb(sessionId, messages);
            } catch (Exception e) {
                log.warn("Failed to persist session {} to DB: {}", sessionId, e.getMessage());
            }
        });
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
        if (Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(sessionId)))) {
            return true;
        }
        // Check DB as fallback
        return sessionRepository.findBySessionId(sessionId).isPresent();
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

    /**
     * DB에서 대화 메시지를 로드하여 UnifiedMessage 리스트로 변환한다.
     */
    private List<UnifiedMessage> loadFromDb(String sessionId) {
        try {
            List<ConversationMessageEntity> dbMessages =
                    messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            if (dbMessages.isEmpty()) return new ArrayList<>();

            List<UnifiedMessage> messages = new ArrayList<>();
            for (ConversationMessageEntity msg : dbMessages) {
                UnifiedMessage.Role role = switch (msg.getRole()) {
                    case "system" -> UnifiedMessage.Role.SYSTEM;
                    case "assistant" -> UnifiedMessage.Role.ASSISTANT;
                    case "tool" -> UnifiedMessage.Role.TOOL_RESULT;
                    default -> UnifiedMessage.Role.USER;
                };
                messages.add(new UnifiedMessage(role, List.of(new ContentBlock.Text(msg.getContent()))));
            }

            // Re-populate Redis cache
            try {
                String key = buildKey(sessionId);
                String json = objectMapper.writeValueAsString(messages);
                redisTemplate.opsForValue().set(key, json, getSessionTtl());
            } catch (Exception e) {
                log.warn("Failed to re-cache session {} in Redis: {}", sessionId, e.getMessage());
            }

            return messages;
        } catch (Exception e) {
            log.warn("Failed to load session {} from DB: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 대화 세션과 메시지를 DB에 영속화한다.
     * 세션이 없으면 생성, 있으면 메시지 카운트/토큰 업데이트.
     */
    private void persistToDb(String sessionId, List<UnifiedMessage> messages) {
        transactionTemplate.executeWithoutResult(status -> {
            ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId)
                    .orElseGet(() -> {
                        ConversationSessionEntity newSession = new ConversationSessionEntity();
                        newSession.setSessionId(sessionId);
                        // 첫 사용자 메시지로 제목 설정
                        messages.stream()
                                .filter(m -> m.role() == UnifiedMessage.Role.USER)
                                .findFirst()
                                .ifPresent(m -> {
                                    String text = extractText(m);
                                    newSession.setTitle(text.length() > 500 ? text.substring(0, 500) : text);
                                });
                        return newSession;
                    });

            session.setMessageCount(messages.size());
            sessionRepository.save(session);

            // 기존 메시지를 삭제하고 전체 재저장 (append 전략 대비 단순하고 안전)
            messageRepository.deleteBySessionId(sessionId);
            for (UnifiedMessage msg : messages) {
                ConversationMessageEntity entity = new ConversationMessageEntity();
                entity.setSessionId(sessionId);
                entity.setRole(msg.role().name().toLowerCase());
                entity.setContent(extractText(msg));
                messageRepository.save(entity);
            }
        });
    }

    private String extractText(UnifiedMessage msg) {
        return msg.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", String::concat);
    }
}
