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
        lenient().when(messageRepository.insertIdempotent(any(), any(), anyInt(), any(), any(), any(), any(), anyInt(),
                any(), any(), any())).thenReturn(1);

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
        stubRedisEmpty();
        stubInsertSucceeds();
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(-1);

        UnifiedMessage assistantWithTool = UnifiedMessage.ofAssistantWithToolUse(
                List.of(new ContentBlock.ToolUse("tu_123", "search", Map.of("q", "hello")))
        );
        sessionStore.appendMessage(sessionId, assistantWithTool);

        ArgumentCaptor<String> contentJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).insertIdempotent(
                any(UUID.class), eq(sessionId), eq(0),
                anyString(),
                messageTypeCaptor.capture(),
                anyString(),
                contentJsonCaptor.capture(),
                anyInt(), any(), any(OffsetDateTime.class), isNull()
        );

        assertThat(messageTypeCaptor.getValue()).isEqualTo(ConversationMessageEntity.TYPE_TOOL_USE);
        assertThat(contentJsonCaptor.getValue())
                .contains("\"type\":\"tool_use\"")
                .contains("\"id\":\"tu_123\"")
                .contains("\"name\":\"search\"");
    }

    @Test
    void appendMessage_text_block_uses_text_message_type() {
        String sessionId = "text-1";
        stubRedisEmpty();
        stubInsertSucceeds();
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(-1);

        sessionStore.appendMessage(sessionId,
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "안녕"));

        verify(messageRepository).insertIdempotent(
                any(UUID.class), eq(sessionId), eq(0),
                eq("user"),
                eq(ConversationMessageEntity.TYPE_TEXT),
                eq("안녕"),
                anyString(),
                anyInt(), any(), any(OffsetDateTime.class), isNull()
        );
    }

    @Test
    void appendMessage_inserts_only_new_messages_after_max_seq() {
        // DB 에 이미 seq 0~2 존재 → 새 메시지는 seq 3 부터 시작
        String sessionId = "resume-1";
        stubRedisEmpty();
        stubInsertSucceeds();

        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                msgEntity(sessionId, 0, "user", "안녕"),
                msgEntity(sessionId, 1, "assistant", "반가워"),
                msgEntity(sessionId, 2, "user", "오늘 날씨")
        ));
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(2);

        sessionStore.appendMessage(sessionId,
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "맑음"));

        verify(messageRepository).insertIdempotent(
                any(UUID.class), eq(sessionId), eq(3),
                eq("assistant"),
                eq(ConversationMessageEntity.TYPE_TEXT),
                eq("맑음"),
                anyString(),
                anyInt(), any(), any(OffsetDateTime.class), isNull()
        );
    }

    @Test
    void appendTurn_saves_turn_id_on_every_message_and_model_on_answer_only() {
        // CR-175 TC-CHT-005: 한 요청의 메시지는 같은 답 번호, 모델은 답에만, 돌려주는 순번은 묶음 첫 순번
        String sessionId = "turn-1";
        UUID turnId = UUID.randomUUID();
        stubRedisEmpty();
        stubInsertSucceeds();
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(4);

        Integer turnSeq = sessionStore.appendTurn(sessionId, List.of(
                        UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문"),
                        UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "답")),
                turnId, "claude-sonnet-5-5", "user-7");

        assertThat(turnSeq).isEqualTo(5);
        verify(messageRepository).insertIdempotent(any(UUID.class), eq(sessionId), eq(5), eq("user"),
                anyString(), anyString(), anyString(), anyInt(), isNull(), any(OffsetDateTime.class),
                eq(turnId.toString()));
        verify(messageRepository).insertIdempotent(any(UUID.class), eq(sessionId), eq(6), eq("assistant"),
                anyString(), anyString(), anyString(), anyInt(), eq("claude-sonnet-5-5"), any(OffsetDateTime.class),
                eq(turnId.toString()));
        verify(sessionRepository).upsertSession(any(UUID.class), eq(sessionId), any(), eq(2),
                any(), eq("user-7"));
    }

    @Test
    void appendTurn_rereads_max_seq_when_slot_taken() {
        // CR-175: 잠금 밖 압축 경계가 순번을 먼저 차지하면 최댓값을 다시 읽어 다음 순번에 넣는다
        String sessionId = "turn-2";
        stubRedisEmpty();
        when(messageRepository.findMaxSeqBySessionId(sessionId)).thenReturn(1, 2);
        when(messageRepository.insertIdempotent(any(), any(), anyInt(), any(), any(), any(), any(), anyInt(),
                any(), any(), any())).thenReturn(0, 1);

        Integer turnSeq = sessionStore.appendTurn(sessionId,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문")), UUID.randomUUID(), null, null);

        assertThat(turnSeq).isEqualTo(3);
        verify(messageRepository).insertIdempotent(any(UUID.class), eq(sessionId), eq(2), anyString(),
                anyString(), anyString(), anyString(), anyInt(), any(), any(OffsetDateTime.class), anyString());
        verify(messageRepository).insertIdempotent(any(UUID.class), eq(sessionId), eq(3), anyString(),
                anyString(), anyString(), anyString(), anyInt(), any(), any(OffsetDateTime.class), anyString());
    }

    @Test
    void rewind_soft_deletes_from_turn_start_and_reloads() {
        // CR-175 TC-CHT-001: 묶음 첫 순번부터 지운 표시(REWIND), Redis 를 지우고 남은 행으로 다시 채운다
        String sessionId = "rw-1";
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                msgEntity(sessionId, 0, "user", "q1", first),
                msgEntity(sessionId, 1, "assistant", "a1", first),
                msgEntity(sessionId, 2, "system", "ctx", second),
                msgEntity(sessionId, 3, "user", "q2", second),
                msgEntity(sessionId, 4, "assistant", "a2", second)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        sessionStore.rewind(sessionId, 2);

        verify(messageRepository).softDeleteFromSeq(eq(sessionId), eq(2), any(OffsetDateTime.class),
                eq(ConversationMessageEntity.DELETE_REASON_REWIND));
        verify(redisTemplate).delete("session:messages:" + sessionId);
    }

    @Test
    void rewind_rejects_seq_inside_a_turn() {
        // CR-175 TC-CHT-002: 묶음 가운데(맥락 메시지 뒤 질문) 순번은 not_turn_start
        String sessionId = "rw-2";
        UUID turn = UUID.randomUUID();
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                msgEntity(sessionId, 0, "system", "ctx", turn),
                msgEntity(sessionId, 1, "user", "q1", turn),
                msgEntity(sessionId, 2, "assistant", "a1", turn)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionStore.rewind(sessionId, 1))
                .isInstanceOf(RewindRejectedException.class)
                .extracting("reason").isEqualTo(RewindRejectedException.Reason.NOT_TURN_START);
        verify(messageRepository, never()).softDeleteFromSeq(anyString(), anyInt(), any(), anyString());
    }

    @Test
    void rewind_rejects_turn_before_compaction_boundary() {
        // CR-175 TC-CHT-004: 마지막 압축 경계보다 앞 묶음은 되감지 않는다
        String sessionId = "rw-3";
        UUID turn = UUID.randomUUID();
        ConversationMessageEntity boundary = msgEntity(sessionId, 2, "system", "summary", null);
        boundary.setMessageType(ConversationMessageEntity.TYPE_COMPACT_BOUNDARY);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                msgEntity(sessionId, 0, "user", "q1", turn),
                msgEntity(sessionId, 1, "assistant", "a1", turn),
                boundary));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionStore.rewind(sessionId, 0))
                .isInstanceOf(RewindRejectedException.class)
                .extracting("reason").isEqualTo(RewindRejectedException.Reason.BEFORE_COMPACTION);
        verify(messageRepository, never()).softDeleteFromSeq(anyString(), anyInt(), any(), anyString());
    }

    private void stubRedisEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(platformSettings.getInt(eq("session.session-ttl-hours"), anyInt())).thenReturn(24);
        when(valueOps.get(anyString())).thenReturn(null);
    }

    private void stubInsertSucceeds() {
        when(messageRepository.insertIdempotent(any(), any(), anyInt(), any(), any(), any(), any(), anyInt(),
                any(), any(), any())).thenReturn(1);
    }

    private ConversationMessageEntity msgEntity(String sessionId, int seq, String role, String content) {
        return msgEntity(sessionId, seq, role, content, null);
    }

    private ConversationMessageEntity msgEntity(String sessionId, int seq, String role, String content, UUID turnId) {
        ConversationMessageEntity e = new ConversationMessageEntity();
        e.setSessionId(sessionId);
        e.setSeq(seq);
        e.setRole(role);
        e.setContent(content);
        e.setContentJson(List.of(Map.of("type", "text", "text", content)));
        e.setMessageType(ConversationMessageEntity.TYPE_TEXT);
        e.setTurnId(turnId);
        return e;
    }
}
