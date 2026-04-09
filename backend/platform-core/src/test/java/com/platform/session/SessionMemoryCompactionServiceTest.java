package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CR-030 Phase 5: SessionMemoryCompactionService 테스트 (PRD-204).
 */
@ExtendWith(MockitoExtension.class)
class SessionMemoryCompactionServiceTest {

    @Mock private ConversationMemoryRepository memoryRepository;

    private SessionMemoryCompactionService service;
    private MemoryService memoryService;

    private static final String SESSION = "sess-1";
    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(memoryRepository);
        service = new SessionMemoryCompactionService(memoryService);
    }

    @Test
    void compact_withSufficientMemories_replacesOldMessages() {
        // 장기 기억 2건 (최소 요건 충족)
        when(memoryRepository.findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(SESSION, "LONG_TERM"))
                .thenReturn(List.of(
                        makeMemory("이전 대화에서 사용자는 결제 시스템을 논의함"),
                        makeMemory("API 인증 방식으로 JWT를 선택함")
                ));

        // 9개 메시지 (system 1 + user/assistant 8)
        List<UnifiedMessage> messages = makeMessages(9, 500);
        int maxTokens = 100_000;

        List<UnifiedMessage> result = service.compact(SESSION, USER, messages, maxTokens);

        assertThat(result).isNotNull();
        assertThat(result.size()).isLessThan(messages.size());
        // 결과에 "[세션 메모리 컨텍스트]" 포함
        boolean hasMemoryContext = result.stream()
                .anyMatch(m -> m.content().toString().contains("[세션 메모리 컨텍스트]"));
        assertThat(hasMemoryContext).isTrue();
    }

    @Test
    void compact_withInsufficientMemories_returnsNull() {
        // 장기 기억 1건 (최소 요건 미달)
        when(memoryRepository.findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(SESSION, "LONG_TERM"))
                .thenReturn(List.of(makeMemory("단일 기억")));

        List<UnifiedMessage> messages = makeMessages(9, 500);

        List<UnifiedMessage> result = service.compact(SESSION, USER, messages, 100_000);

        assertThat(result).isNull();
    }

    @Test
    void compact_withTooFewMessages_returnsNull() {
        when(memoryRepository.findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(SESSION, "LONG_TERM"))
                .thenReturn(List.of(
                        makeMemory("기억1"), makeMemory("기억2")
                ));

        // system + user 2개만 (비-system 2개 → 압축 불필요)
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "system"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hello"),
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "hi")
        );

        List<UnifiedMessage> result = service.compact(SESSION, USER, messages, 100_000);

        assertThat(result).isNull();
    }

    @Test
    void compact_resultExceedsMaxTokens_returnsNull() {
        when(memoryRepository.findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(SESSION, "LONG_TERM"))
                .thenReturn(List.of(
                        makeMemory("x".repeat(50_000)), // 매우 긴 메모리
                        makeMemory("y".repeat(50_000))
                ));

        List<UnifiedMessage> messages = makeMessages(9, 20_000);

        // 매우 작은 maxTokens → 메모리 주입 후에도 초과
        List<UnifiedMessage> result = service.compact(SESSION, USER, messages, 1_000);

        assertThat(result).isNull();
    }

    @Test
    void compact_preservesSystemMessages() {
        when(memoryRepository.findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(SESSION, "LONG_TERM"))
                .thenReturn(List.of(
                        makeMemory("기억1"), makeMemory("기억2")
                ));

        List<UnifiedMessage> messages = makeMessages(9, 500);

        List<UnifiedMessage> result = service.compact(SESSION, USER, messages, 100_000);

        assertThat(result).isNotNull();
        long systemCount = result.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .count();
        // 원본 system 1개 + 메모리 컨텍스트 1개 = 2개
        assertThat(systemCount).isEqualTo(2);
    }

    // ── Helper ──

    private ConversationMemoryEntity makeMemory(String content) {
        ConversationMemoryEntity e = new ConversationMemoryEntity();
        e.setSessionId(SESSION);
        e.setUserId(USER);
        e.setMemoryType("LONG_TERM");
        e.setScope("PRIVATE");
        e.setContent(content);
        return e;
    }

    private List<UnifiedMessage> makeMessages(int count, int charsPerMessage) {
        List<UnifiedMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = "msg-" + i + "-" + "x".repeat(charsPerMessage);
            messages.add(UnifiedMessage.ofText(
                    i == 0 ? UnifiedMessage.Role.SYSTEM : UnifiedMessage.Role.USER, text));
        }
        return messages;
    }
}
