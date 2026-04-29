package com.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-077: conversation_sessions UNIQUE race retry 동작 검증.
 *
 * <p>위젯 SDK가 같은 session_id로 메시지 #1/#2/#3을 거의 동시에 보내면
 * SessionStore.persistToDb 비동기 VT가 병렬 INSERT를 시도하여 UNIQUE 위반.
 * 이전에는 1회 retry — 첫 충돌만 회복하고 두 번째 동시 충돌이 그대로 throw됨.
 * 본 변경으로 3회 retry까지 견디고, 마지막 attempt 실패 시 throw.
 */
@ExtendWith(MockitoExtension.class)
class SessionStoreRaceRetryTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private PlatformSettingsService platformSettings;

    private TransactionTemplate transactionTemplate;
    private SessionStore sessionStore;

    @BeforeEach
    void setUp() {
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
                redisTemplate, new ObjectMapper(),
                sessionRepository, messageRepository,
                transactionTemplate, platformSettings
        );
    }

    @Test
    void setWorkspaceRefIfAbsent_recovers_after_two_consecutive_race_violations() {
        // 첫 attempt = empty → 신규 save → DataIntegrityViolation
        // 두번째 attempt = 다시 empty → 신규 save → 또 DataIntegrityViolation
        // 세번째 attempt = race-winner row 발견 → workspaceRef 동일 → save 생략 (또는 update)
        ConversationSessionEntity winner = new ConversationSessionEntity();
        winner.setSessionId("sess-1");
        winner.setWorkspaceRef("ws-A");

        when(sessionRepository.findBySessionIdIncludingDeleted("sess-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(sessionRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup #1"))
                .thenThrow(new DataIntegrityViolationException("dup #2"));

        // 3번째 attempt 에서 winner row 발견 + workspaceRef 동일 → save 호출 없이 정상 종료
        sessionStore.setWorkspaceRefIfAbsent("sess-1", "ws-A");

        verify(sessionRepository, times(3)).findBySessionIdIncludingDeleted("sess-1");
        verify(sessionRepository, times(2)).save(any());
    }

    @Test
    void setWorkspaceRefIfAbsent_throws_after_three_failed_attempts() {
        // 3회 모두 race로 충돌 — 마지막 attempt에서 throw
        when(sessionRepository.findBySessionIdIncludingDeleted("sess-2"))
                .thenReturn(Optional.empty());
        when(sessionRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> sessionStore.setWorkspaceRefIfAbsent("sess-2", "ws-B"))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(sessionRepository, times(3)).save(any());
    }

    @Test
    void setWorkspaceRefIfAbsent_skips_when_workspaceRef_blank() {
        sessionStore.setWorkspaceRefIfAbsent("sess-3", "");
        sessionStore.setWorkspaceRefIfAbsent("sess-3", null);
        verifyNoInteractions(sessionRepository);
    }
}
