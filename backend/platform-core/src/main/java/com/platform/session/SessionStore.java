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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * 세션 메시지 저장소.
 *
 * <p>CR-083 정공 정리:
 * <ul>
 *   <li><b>per-session 직렬화</b> — sessionId 별 Semaphore(1) 로 Redis RMW + DB persist 직렬화.
 *       lost-update 와 count race 동시 차단.</li>
 *   <li><b>멀티블록 보존</b> — content_json JSONB 로 ContentBlock 리스트(Text/ToolUse/ToolResult/Image/Thinking) 통째 직렬화.
 *       Tool 컨텍스트 영구 손실 + Redis/DB 진실 불일치 차단.</li>
 *   <li><b>seq 기반 idempotent INSERT</b> — UNIQUE(session_id, seq) + ON CONFLICT DO NOTHING.
 *       카운트 비교 폐기.</li>
 *   <li><b>단일 가상스레드 Executor</b> — Thread.ofVirtual().start() 무제한 → ExecutorService 1개.
 *       VT 폭주 + DB 풀 고갈 차단.</li>
 * </ul>
 */
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

    /** CR-083: sessionId 별 Semaphore(1) — Redis RMW + DB persist 직렬화. */
    private final ConcurrentHashMap<String, Semaphore> sessionLocks = new ConcurrentHashMap<>();

    /**
     * CR-083: List&lt;ContentBlock&gt; 직렬화 시 Jackson polymorphic info("type":"tool_use" 등)
     * 누락 방지를 위해 ObjectWriter 를 명시적 타입으로 미리 구성. writeValueAsString(list)
     * 만 쓰면 generic erasure 로 타입 메타가 빠진다.
     */
    private final ObjectWriter contentBlockListWriter;

    /** CR-083: 단일 가상스레드 Executor — VT 무제한 생성 차단. */
    private final ExecutorService persistExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("session-persist-", 0).factory());

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
        this.contentBlockListWriter = objectMapper.writerFor(
                objectMapper.getTypeFactory().constructCollectionType(List.class, ContentBlock.class));
    }

    @PreDestroy
    void shutdown() {
        persistExecutor.shutdown();
    }

    private Duration getSessionTtl() {
        int hours = platformSettings.getInt("session.session-ttl-hours", 24);
        return Duration.ofHours(hours);
    }

    private Semaphore lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Semaphore(1));
    }

    public List<UnifiedMessage> getMessages(String sessionId) {
        // CR-083: lock 안에서 Redis 조회 → loadFromDb 폴백 → 캐시 SET 일관 처리.
        Semaphore lock = lockFor(sessionId);
        lock.acquireUninterruptibly();
        try {
            return readMessagesLocked(sessionId);
        } finally {
            lock.release();
        }
    }

    private List<UnifiedMessage> readMessagesLocked(String sessionId) {
        try {
            String key = buildKey(sessionId);
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<>() {});
            }
            return loadFromDbAndCache(sessionId);
        } catch (Exception e) {
            log.warn("Failed to load session {} from Redis, trying DB: {}", sessionId, e.getMessage());
            return loadFromDbAndCache(sessionId);
        }
    }

    public void saveMessages(String sessionId, List<UnifiedMessage> messages) {
        // CR-083: Redis SET + 비동기 DB persist 모두 lock 안에서 트리거.
        // SET 자체는 lock 안에서 즉시 끝나고, persist 만 Executor 로 비동기.
        Semaphore lock = lockFor(sessionId);
        lock.acquireUninterruptibly();
        try {
            saveToRedisLocked(sessionId, messages);
        } finally {
            lock.release();
        }

        // 비동기 DB persist — TenantContext 수동 전파.
        final String propagatedTenantId = TenantContext.getTenantId();
        final List<UnifiedMessage> snapshot = List.copyOf(messages);
        persistExecutor.submit(() -> {
            if (propagatedTenantId != null) {
                TenantContext.setTenantId(propagatedTenantId);
            }
            try {
                persistToDb(sessionId, snapshot);
            } catch (Exception e) {
                log.warn("Failed to persist session {} to DB: {}", sessionId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void saveToRedisLocked(String sessionId, List<UnifiedMessage> messages) {
        try {
            String key = buildKey(sessionId);
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, json, getSessionTtl());
        } catch (Exception e) {
            log.warn("Failed to save session {} to Redis: {}", sessionId, e.getMessage());
        }
    }

    public void appendMessage(String sessionId, UnifiedMessage message) {
        // CR-083: lock 으로 read-modify-write 직렬화 → lost-update 차단.
        Semaphore lock = lockFor(sessionId);
        lock.acquireUninterruptibly();
        List<UnifiedMessage> snapshot;
        try {
            List<UnifiedMessage> messages = readMessagesLocked(sessionId);
            messages.add(message);
            saveToRedisLocked(sessionId, messages);
            snapshot = List.copyOf(messages);
        } finally {
            lock.release();
        }

        // 비동기 DB persist (lock 밖)
        final String propagatedTenantId = TenantContext.getTenantId();
        persistExecutor.submit(() -> {
            if (propagatedTenantId != null) {
                TenantContext.setTenantId(propagatedTenantId);
            }
            try {
                persistToDb(sessionId, snapshot);
            } catch (Exception e) {
                log.warn("Failed to persist session {} to DB: {}", sessionId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        });
    }

    public void clearSession(String sessionId) {
        Semaphore lock = lockFor(sessionId);
        lock.acquireUninterruptibly();
        try {
            redisTemplate.delete(buildKey(sessionId));
        } finally {
            lock.release();
        }
    }

    public boolean hasSession(String sessionId) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(sessionId)))) {
            return true;
        }
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
     * CR-083: DB 에서 메시지 로드 후 멀티블록(content_json) 복원. Redis 캐시도 같이 SET.
     * lock 안에서만 호출되므로 Redis 덮어쓰기 race 없음.
     */
    private List<UnifiedMessage> loadFromDbAndCache(String sessionId) {
        try {
            List<ConversationMessageEntity> dbMessages =
                    messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            if (dbMessages.isEmpty()) return new ArrayList<>();

            List<UnifiedMessage> messages = new ArrayList<>();
            for (ConversationMessageEntity msg : dbMessages) {
                UnifiedMessage.Role role = parseRole(msg.getRole());
                List<ContentBlock> blocks = decodeContentJson(msg);
                messages.add(new UnifiedMessage(role, blocks));
            }

            // Redis 재캐싱
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

    private UnifiedMessage.Role parseRole(String role) {
        return switch (role) {
            case "system", "SYSTEM" -> UnifiedMessage.Role.SYSTEM;
            case "assistant", "ASSISTANT" -> UnifiedMessage.Role.ASSISTANT;
            case "tool", "TOOL_RESULT" -> UnifiedMessage.Role.TOOL_RESULT;
            default -> UnifiedMessage.Role.USER;
        };
    }

    /** CR-083: content_json 이 있으면 거기서 복원, 없으면 content 텍스트 폴백. */
    private List<ContentBlock> decodeContentJson(ConversationMessageEntity msg) {
        List<Map<String, Object>> raw = msg.getContentJson();
        if (raw == null || raw.isEmpty()) {
            // 폴백: 마이그레이션 직후 또는 손실된 row
            String text = msg.getContent() != null ? msg.getContent() : "";
            return List.of(new ContentBlock.Text(text));
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map<String, Object> block : raw) {
            try {
                ContentBlock decoded = objectMapper.convertValue(block, ContentBlock.class);
                blocks.add(decoded);
            } catch (Exception e) {
                log.warn("Failed to decode ContentBlock from session {} msg {}: {}",
                        msg.getSessionId(), msg.getId(), e.getMessage());
                String text = msg.getContent() != null ? msg.getContent() : "";
                blocks.add(new ContentBlock.Text(text));
                break;
            }
        }
        return blocks;
    }

    /**
     * CR-083: 세션 + 메시지 영속화. seq 기반 idempotent.
     * persistExecutor 안에서만 호출되며, 같은 sessionId 작업은 순차 실행 보장.
     */
    private void persistToDb(String sessionId, List<UnifiedMessage> messages) {
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
        // CR-083: native ON CONFLICT(session_id) DO UPDATE 그대로 유지 (CR-077 이전 구현).
        String title = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.USER)
                .findFirst()
                .map(this::extractText)
                .map(t -> t.length() > 500 ? t.substring(0, 500) : t)
                .orElse(null);
        transactionTemplate.executeWithoutResult(status -> {
            sessionRepository.upsertSession(
                    UUID.randomUUID(),
                    sessionId,
                    title,
                    messages.size(),
                    "chat");
        });
    }

    /**
     * CR-083: seq 기반 idempotent INSERT. ON CONFLICT(session_id, seq) DO NOTHING 으로 race 무해 처리.
     * 카운트 비교 / "DB has X but memory has Y" 로그 없음.
     */
    private void appendNewMessages(String sessionId, List<UnifiedMessage> messages) {
        transactionTemplate.executeWithoutResult(status -> {
            int maxSeq = messageRepository.findMaxSeqBySessionId(sessionId); // -1 if empty
            int startSeq = maxSeq + 1;
            for (int i = startSeq; i < messages.size(); i++) {
                UnifiedMessage msg = messages.get(i);
                String contentJson;
                try {
                    // CR-083: List<ContentBlock> 명시 타입으로 직렬화 → "type":"tool_use" 등 polymorphic info 보존
                    contentJson = contentBlockListWriter.writeValueAsString(msg.content());
                } catch (Exception e) {
                    log.warn("Failed to serialize ContentBlocks for session {} seq {}: {}",
                            sessionId, i, e.getMessage());
                    contentJson = "[]";
                }
                messageRepository.insertIdempotent(
                        UUID.randomUUID(),
                        sessionId,
                        i,
                        msg.role().name().toLowerCase(),
                        resolveMessageType(msg),
                        extractText(msg),
                        contentJson,
                        0,
                        null,
                        OffsetDateTime.now()
                );
            }
        });
    }

    /** CR-083: ContentBlock 종류로 messageType 결정. ToolUse/ToolResult 가 섞인 메시지는 첫 비-텍스트 블록 우선. */
    private String resolveMessageType(UnifiedMessage msg) {
        for (ContentBlock b : msg.content()) {
            if (b instanceof ContentBlock.ToolUse) return ConversationMessageEntity.TYPE_TOOL_USE;
            if (b instanceof ContentBlock.ToolResult) return ConversationMessageEntity.TYPE_TOOL_RESULT;
        }
        return ConversationMessageEntity.TYPE_TEXT;
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
