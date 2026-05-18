package com.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConversationMessageEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-083: SessionStore 정합성 정공 정리 검증.
 *
 * <ul>
 *   <li>per-session lock 으로 appendMessage RMW race 차단</li>
 *   <li>멀티블록 (ToolUse/ToolResult) 보존</li>
 *   <li>messageType 정확 매핑</li>
 *   <li>seq 기반 INSERT 호출 검증</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionStoreCr083Test {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private PlatformSettingsService platformSettings;

    private TransactionTemplate transactionTemplate;
    private ObjectMapper objectMapper;
    private SessionStore sessionStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        transactionTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
            @Override
            public void executeWithoutResult(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                action.accept(null);
            }
        };
        sessionStore = new SessionStore(
                redisTemplate, objectMapper,
                sessionRepository, messageRepository,
                transactionTemplate, platformSettings
        );
    }

    @Test
    void appendMessage_serializes_concurrent_calls_no_lost_update() throws Exception {
        // Redis 가 빈 상태로 시작 → lost-update 가 일어난다면 마지막 SET 의 messages 길이가 1이 됨.
        // 직렬화되면 길이가 정확히 N 이 됨.
        String sessionId = "race-1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(platformSettings.getInt(eq("session.session-ttl-hours"), anyInt())).thenReturn(24);

        // Redis 의 in-memory 시뮬레이션
        final String[] redisState = {null};
        when(valueOps.get(anyString())).thenAnswer(inv -> redisState[0]);
        doAnswer(inv -> {
            redisState[0] = inv.getArgument(1);
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        when(messageRepository.findMaxSeqBySessionId(anyString())).thenReturn(-1);

        int n = 20;
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                    sessionStore.appendMessage(sessionId,
                            UnifiedMessage.ofText(UnifiedMessage.Role.USER, "msg-" + idx));
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();

        // Redis 최종 상태에 N 개 모두 들어있어야 함 (lost-update 없음)
        List<UnifiedMessage> finalState = objectMapper.readValue(
                redisState[0],
                new com.fasterxml.jackson.core.type.TypeReference<List<UnifiedMessage>>() {});
        assertThat(finalState).hasSize(n);
    }

    @Test
    void appendMessage_preserves_tool_use_block_in_db() {
        // ToolUse 블록을 가진 메시지가 들어오면 content_json 에 직렬화되고 messageType 이 TOOL_USE 여야 한다.
        String sessionId = "tool-1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(platformSettings.getInt(eq("session.session-ttl-hours"), anyInt())).thenReturn(24);
        when(valueOps.get(anyString())).thenReturn(null);
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(-1);

        UnifiedMessage assistantWithTool = UnifiedMessage.ofAssistantWithToolUse(
                List.of(new ContentBlock.ToolUse("tu_123", "search", Map.of("q", "hello")))
        );
        sessionStore.appendMessage(sessionId, assistantWithTool);

        // 비동기 persist 완료까지 대기 — insertIdempotent 호출 한 번이라도 잡힐 때까지
        ArgumentCaptor<String> contentJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageTypeCaptor = ArgumentCaptor.forClass(String.class);
        await(() -> mockingDetails(messageRepository).getInvocations().stream()
                .anyMatch(inv -> "insertIdempotent".equals(inv.getMethod().getName())));

        verify(messageRepository).insertIdempotent(
                any(UUID.class), eq(sessionId), eq(0),
                anyString(),
                messageTypeCaptor.capture(),
                anyString(),
                contentJsonCaptor.capture(),
                anyInt(), any(), any(OffsetDateTime.class)
        );

        // messageType: TYPE_TOOL_USE 매핑됐는가
        assertThat(messageTypeCaptor.getValue()).isEqualTo(ConversationMessageEntity.TYPE_TOOL_USE);
        // content_json: ToolUse 가 JSON 으로 보존됐는가
        assertThat(contentJsonCaptor.getValue())
                .contains("\"type\":\"tool_use\"")
                .contains("\"id\":\"tu_123\"")
                .contains("\"name\":\"search\"");
    }

    @Test
    void appendMessage_text_block_uses_text_message_type() {
        String sessionId = "text-1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(platformSettings.getInt(eq("session.session-ttl-hours"), anyInt())).thenReturn(24);
        when(valueOps.get(anyString())).thenReturn(null);
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(-1);

        sessionStore.appendMessage(sessionId,
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "안녕"));

        await(() -> {
            try {
                verify(messageRepository, atLeastOnce()).insertIdempotent(
                        any(UUID.class), eq(sessionId), eq(0),
                        eq("user"),
                        eq(ConversationMessageEntity.TYPE_TEXT),
                        eq("안녕"),
                        anyString(),
                        anyInt(), any(), any(OffsetDateTime.class)
                );
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
    }

    @Test
    void appendMessage_inserts_only_new_messages_after_max_seq() {
        // DB 에 이미 seq 0~2 존재 → 새 메시지는 seq 3 부터 시작
        String sessionId = "resume-1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(platformSettings.getInt(eq("session.session-ttl-hours"), anyInt())).thenReturn(24);
        when(valueOps.get(anyString())).thenReturn(null);

        // loadFromDb 가 0,1,2 를 돌려주도록
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                msgEntity(sessionId, 0, "user", "안녕"),
                msgEntity(sessionId, 1, "assistant", "반가워"),
                msgEntity(sessionId, 2, "user", "오늘 날씨")
        ));
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(2);

        sessionStore.appendMessage(sessionId,
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "맑음"));

        // seq 3 으로 INSERT 되어야 함
        await(() -> {
            try {
                verify(messageRepository, atLeastOnce()).insertIdempotent(
                        any(UUID.class), eq(sessionId), eq(3),
                        eq("assistant"),
                        eq(ConversationMessageEntity.TYPE_TEXT),
                        eq("맑음"),
                        anyString(),
                        anyInt(), any(), any(OffsetDateTime.class)
                );
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
    }

    private ConversationMessageEntity msgEntity(String sessionId, int seq, String role, String content) {
        ConversationMessageEntity e = new ConversationMessageEntity();
        e.setSessionId(sessionId);
        e.setSeq(seq);
        e.setRole(role);
        e.setContent(content);
        e.setContentJson(List.of(Map.of("type", "text", "text", content)));
        e.setMessageType(ConversationMessageEntity.TYPE_TEXT);
        return e;
    }

    /** 비동기 persist 가 끝날 때까지 짧게 대기 (최대 2초). */
    private void await(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        // 실패 시 마지막 한 번 더 호출 → 실제 AssertionError throw
        if (!cond.getAsBoolean()) {
            throw new AssertionError("condition not met within timeout");
        }
    }
}
