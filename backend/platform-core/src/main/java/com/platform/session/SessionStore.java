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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li><b>seq 기반 idempotent INSERT</b> — 지우지 않은 행의 UNIQUE(session_id, seq) + ON CONFLICT DO NOTHING.
 *       카운트 비교 폐기.</li>
 * </ul>
 *
 * <p>CR-175: 대화 요청 한 번의 메시지는 {@link #appendTurn} 으로 한 잠금 안에서 Redis 와 DB 에 함께 저장한다.
 * 되감기({@link #rewind})가 앞 요청의 저장과 엇갈리지 않게 하려고 비동기 저장을 없앴다.
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
        appendTurn(sessionId, List.of(message), null, null, null);
    }

    /**
     * CR-175: 대화 요청 한 번이 만든 메시지를 한 잠금 안에서 붙이고 DB 에 바로 저장한다(PRD-369).
     *
     * <p>DB 에는 이번 요청 메시지만 넣고 순번은 지우지 않은 행의 최댓값 다음부터 매긴다. 예전처럼
     * Redis 목록 위치를 순번으로 쓰지 않으므로, 압축 경계처럼 DB 에만 있는 행이 끼어도 메시지가 빠지지 않는다.
     * 저장을 잠금 안에서 끝내므로 되감기가 앞 요청의 늦은 저장과 엇갈리지 않는다.
     *
     * @param turnId   이번 요청 번호. null 이면 번호 없이 저장한다
     * @param model    답을 만든 모델. 답 메시지에만 적는다
     * @param ownerRef 세션 주인(위젯 토큰 user_ref). 세션을 처음 만들 때만 적는다
     * @return 이번 요청 첫 메시지의 DB 순번. DB 저장에 실패하면 null(대화 기록은 Redis 에 남는다)
     */
    public Integer appendTurn(String sessionId, List<UnifiedMessage> newMessages,
                              UUID turnId, String model, String ownerRef) {
        if (newMessages == null || newMessages.isEmpty()) return null;
        Semaphore lock = lockFor(sessionId);
        lock.acquireUninterruptibly();
        try {
            List<UnifiedMessage> messages = new ArrayList<>(readMessagesLocked(sessionId));
            messages.addAll(newMessages);
            saveToRedisLocked(sessionId, messages);
            try {
                upsertSessionWithRetry(sessionId, messages, ownerRef);
                return insertTurnMessages(sessionId, newMessages, turnId, model);
            } catch (Exception e) {
                log.warn("Failed to persist session {} to DB: {}", sessionId, e.getMessage());
                return null;
            }
        } finally {
            lock.release();
        }
    }

    /**
     * CR-175 BIZ-148: 되감기. fromSeq 부터 뒤 메시지에 지운 표시를 하고 Redis 기록을 남은 행으로 다시 만든다.
     *
     * <p>fromSeq 는 대화 요청 한 번이 저장한 메시지 묶음의 첫 순번이어야 한다. 붙이는 앱이 질문 앞에 보낸
     * 맥락 메시지까지 한 묶음으로 지우기 위해서다. 지운 표시와 Redis 비우기는 한 트랜잭션이라, Redis 를
     * 비우지 못하면 지운 표시도 되돌린다.
     *
     * @throws RewindRejectedException 묶음 첫 순번이 아니거나 마지막 압축 경계보다 앞일 때
     */
    public void rewind(String sessionId, int fromSeq) {
        Semaphore lock = lockFor(sessionId);
        lock.acquireUninterruptibly();
        try {
            List<ConversationMessageEntity> live = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            ConversationMessageEntity target = live.stream()
                    .filter(m -> m.getSeq() == fromSeq)
                    .findFirst()
                    .orElse(null);
            if (target == null || target.getTurnId() == null || !isTurnStart(live, target)) {
                throw new RewindRejectedException(RewindRejectedException.Reason.NOT_TURN_START);
            }
            boolean compactedAfter = live.stream().anyMatch(m ->
                    ConversationMessageEntity.TYPE_COMPACT_BOUNDARY.equals(m.getMessageType()) && m.getSeq() > fromSeq);
            if (compactedAfter) {
                throw new RewindRejectedException(RewindRejectedException.Reason.BEFORE_COMPACTION);
            }
            String key = buildKey(sessionId);
            transactionTemplate.executeWithoutResult(status -> {
                messageRepository.softDeleteFromSeq(sessionId, fromSeq, OffsetDateTime.now(),
                        ConversationMessageEntity.DELETE_REASON_REWIND);
                redisTemplate.delete(key);
            });
            loadFromDbAndCache(sessionId);
        } finally {
            lock.release();
        }
    }

    private static boolean isTurnStart(List<ConversationMessageEntity> live, ConversationMessageEntity target) {
        return live.stream()
                .filter(m -> target.getTurnId().equals(m.getTurnId()))
                .allMatch(m -> m.getSeq() >= target.getSeq());
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

    private void upsertSessionWithRetry(String sessionId, List<UnifiedMessage> messages, String ownerRef) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                upsertSession(sessionId, messages, ownerRef);
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

    private void upsertSession(String sessionId, List<UnifiedMessage> messages, String ownerRef) {
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
                    "chat",
                    ownerRef);
        });
    }

    /**
     * CR-175: 이번 요청 메시지를 지우지 않은 행의 최댓값 다음 순번부터 넣는다.
     * 잠금 밖에서 넣는 압축 경계가 같은 순번을 먼저 차지하면 최댓값을 다시 읽어 다음 순번으로 넣는다.
     */
    private Integer insertTurnMessages(String sessionId, List<UnifiedMessage> newMessages,
                                       UUID turnId, String model) {
        String turnIdText = turnId != null ? turnId.toString() : null;
        return transactionTemplate.execute(status -> {
            Integer firstSeq = null;
            int seq = messageRepository.findMaxSeqBySessionId(sessionId) + 1;
            for (UnifiedMessage msg : newMessages) {
                String contentJson;
                try {
                    // CR-083: List<ContentBlock> 명시 타입으로 직렬화 → "type":"tool_use" 등 polymorphic info 보존
                    contentJson = contentBlockListWriter.writeValueAsString(msg.content());
                } catch (Exception e) {
                    log.warn("Failed to serialize ContentBlocks for session {} seq {}: {}",
                            sessionId, seq, e.getMessage());
                    contentJson = "[]";
                }
                String messageModel = msg.role() == UnifiedMessage.Role.ASSISTANT ? model : null;
                int maxAttempts = 3;
                int attempt = 1;
                while (insertMessage(sessionId, seq, msg, contentJson, messageModel, turnIdText) == 0) {
                    if (attempt++ >= maxAttempts) {
                        throw new IllegalStateException("conversation_messages seq conflict: session="
                                + sessionId + " seq=" + seq);
                    }
                    seq = messageRepository.findMaxSeqBySessionId(sessionId) + 1;
                }
                if (firstSeq == null) firstSeq = seq;
                seq++;
            }
            return firstSeq;
        });
    }

    private int insertMessage(String sessionId, int seq, UnifiedMessage msg, String contentJson,
                              String model, String turnId) {
        return messageRepository.insertIdempotent(
                UUID.randomUUID(),
                sessionId,
                seq,
                msg.role().name().toLowerCase(),
                resolveMessageType(msg),
                extractText(msg),
                contentJson,
                0,
                model,
                OffsetDateTime.now(),
                turnId);
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
