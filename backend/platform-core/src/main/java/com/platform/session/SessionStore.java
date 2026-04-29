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
        // CR-045: TenantContext(ThreadLocal)를 자식 가상 스레드에 수동 전파해야
        // Hibernate DataSource 라우팅이 테넌트 DB로 감.
        final String propagatedTenantId = com.platform.tenant.TenantContext.getTenantId();
        Thread.ofVirtual().start(() -> {
            if (propagatedTenantId != null) {
                com.platform.tenant.TenantContext.setTenantId(propagatedTenantId);
            }
            try {
                persistToDb(sessionId, messages);
            } catch (Exception e) {
                log.warn("Failed to persist session {} to DB: {}", sessionId, e.getMessage());
            } finally {
                com.platform.tenant.TenantContext.clear();
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
        // CR-077: 세션 upsert는 동시 INSERT race 대비 최대 3회 재시도.
        // 위젯 SDK가 같은 session_id로 메시지 #1/#2/#3을 거의 동시에 보내면
        // 비동기 VT 3개가 병렬로 첫 진입하여 1회 retry로는 부족할 수 있다.
        upsertSessionWithRetry(sessionId, messages);
        appendNewMessages(sessionId, messages);
    }

    private void upsertSessionWithRetry(String sessionId, List<UnifiedMessage> messages) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                upsertSession(sessionId, messages);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt == maxAttempts) {
                    log.warn("Session {} upsert failed after {} attempts: {}",
                            sessionId, maxAttempts, e.getMessage());
                    throw e;
                }
                log.debug("Session {} insert race (attempt {}/{}), retrying",
                        sessionId, attempt, maxAttempts);
            }
        }
    }

    private void upsertSession(String sessionId, List<UnifiedMessage> messages) {
        transactionTemplate.executeWithoutResult(status -> {
            // CR-046: 영속 시 soft-deleted row를 발견하면 그 row를 그대로 사용해야 UNIQUE 충돌 회피.
            ConversationSessionEntity session = sessionRepository.findBySessionIdIncludingDeleted(sessionId)
                    .orElseGet(() -> {
                        ConversationSessionEntity newSession = new ConversationSessionEntity();
                        newSession.setSessionId(sessionId);
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
        });
    }

    private void appendNewMessages(String sessionId, List<UnifiedMessage> messages) {
        transactionTemplate.executeWithoutResult(status -> {
            long existing = messageRepository.countBySessionId(sessionId);
            if (existing >= messages.size()) {
                if (existing > messages.size()) {
                    log.warn("Session {} DB has {} messages but memory has {} — skipping append",
                            sessionId, existing, messages.size());
                }
                return;
            }
            for (int i = (int) existing; i < messages.size(); i++) {
                UnifiedMessage msg = messages.get(i);
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

    // ── CR-045: workspaceRef 세션 메타 접근 ──

    /**
     * 세션에 연결된 workspaceRef를 조회. 세션 미존재 시 null.
     */
    public String getWorkspaceRef(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .map(ConversationSessionEntity::getWorkspaceRef)
                .orElse(null);
    }

    /**
     * 세션에 workspaceRef를 저장. 세션 미존재 시 생성.
     * 이미 값이 있고 다르면 IllegalStateException (BIZ-091).
     */
    public void setWorkspaceRefIfAbsent(String sessionId, String workspaceRef) {
        if (workspaceRef == null || workspaceRef.isBlank()) return;
        // CR-077: 동시 첫 메시지 흐름에서 setWorkspaceRefIfAbsent와 persistToDb가
        // 같은 session_id를 동시에 INSERT 시도할 수 있어 1회 race retry 추가.
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                doSetWorkspaceRefIfAbsent(sessionId, workspaceRef);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt == maxAttempts) {
                    log.warn("setWorkspaceRefIfAbsent {} failed after {} attempts: {}",
                            sessionId, maxAttempts, e.getMessage());
                    throw e;
                }
                log.debug("setWorkspaceRefIfAbsent {} race (attempt {}/{}), retrying",
                        sessionId, attempt, maxAttempts);
            }
        }
    }

    private void doSetWorkspaceRefIfAbsent(String sessionId, String workspaceRef) {
        transactionTemplate.executeWithoutResult(status -> {
            // CR-046: soft-deleted row 포함 조회로 UNIQUE 충돌 회피.
            ConversationSessionEntity session = sessionRepository.findBySessionIdIncludingDeleted(sessionId)
                    .orElseGet(() -> {
                        ConversationSessionEntity s = new ConversationSessionEntity();
                        s.setSessionId(sessionId);
                        return s;
                    });
            String existing = session.getWorkspaceRef();
            if (existing != null && !existing.isBlank() && !existing.equals(workspaceRef)) {
                throw new IllegalStateException(
                        "세션 workspaceRef 충돌: existing=" + existing + ", requested=" + workspaceRef);
            }
            if (existing == null || existing.isBlank()) {
                session.setWorkspaceRef(workspaceRef);
                sessionRepository.save(session);
            }
        });
    }
}
