package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.repository.ConversationMemoryRepository;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-031 PRD-213: MemoryAutoExtractService 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class MemoryAutoExtractServiceTest {

    @Mock private LLMAdapterRegistry adapterRegistry;
    @Mock private ConversationMemoryRepository memoryRepository;
    @Mock private MemoryService memoryService;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private LLMAdapter haiku;

    private MemoryAutoExtractService service;

    @BeforeEach
    void setUp() {
        service = new MemoryAutoExtractService(adapterRegistry, memoryRepository, memoryService, promptTemplateService);
    }

    @Test
    void extractIfEligible_tooFewMessages_skips() {
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi"),
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "hello"));

        service.extractIfEligible("sess-1", "user-1", messages);

        verifyNoInteractions(adapterRegistry);
    }

    @Test
    void extractIfEligible_nullSessionId_skips() {
        service.extractIfEligible(null, "user-1", List.of());
        verifyNoInteractions(adapterRegistry);
    }

    @Test
    void extractMemories_parsesLLMResponse() throws Exception {
        String llmOutput = """
                - [task] 프로젝트는 Spring Boot 3.4 사용
                - [person] 시니어 Java 개발자
                - [pattern] PR 전에 테스트 실행 필수
                """;

        when(adapterRegistry.getAdapter("anthropic/claude-haiku-4-5")).thenReturn(haiku);
        when(haiku.chat(any())).thenReturn(CompletableFuture.completedFuture(
                new LLMResponse("id", "model",
                        List.of(new ContentBlock.Text(llmOutput)),
                        List.of(),
                        new TokenUsage(100, 50),
                        LLMResponse.FinishReason.END, 0L, 0.001)));

        List<UnifiedMessage> messages = buildMessages(12);
        List<String> extracted = service.extractMemories(messages);

        assertThat(extracted).hasSize(3);
        assertThat(extracted.get(0)).contains("프로젝트는 Spring Boot 3.4");
    }

    @Test
    void extractMemories_emptyResponse_returnsEmpty() throws Exception {
        when(adapterRegistry.getAdapter("anthropic/claude-haiku-4-5")).thenReturn(haiku);
        when(haiku.chat(any())).thenReturn(CompletableFuture.completedFuture(
                new LLMResponse("id", "model",
                        List.of(new ContentBlock.Text("없음")),
                        List.of(),
                        new TokenUsage(10, 5),
                        LLMResponse.FinishReason.END, 0L, 0.0)));

        List<String> extracted = service.extractMemories(buildMessages(12));
        assertThat(extracted).isEmpty();
    }

    @Test
    void isSimilar_containsRelation_returnsTrue() {
        assertThat(MemoryAutoExtractService.isSimilar(
                "사용자는 시니어 Java 개발자", "시니어 Java 개발자")).isTrue();
    }

    @Test
    void isSimilar_differentContent_returnsFalse() {
        assertThat(MemoryAutoExtractService.isSimilar(
                "프로젝트 A는 React 사용", "사용자는 Python 전문가")).isFalse();
    }

    @Test
    void isSimilar_nullInput_returnsFalse() {
        assertThat(MemoryAutoExtractService.isSimilar(null, "test")).isFalse();
        assertThat(MemoryAutoExtractService.isSimilar("test", null)).isFalse();
    }

    private List<UnifiedMessage> buildMessages(int count) {
        List<UnifiedMessage> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var role = i % 2 == 0 ? UnifiedMessage.Role.USER : UnifiedMessage.Role.ASSISTANT;
            msgs.add(UnifiedMessage.ofText(role, "메시지 " + i));
        }
        return msgs;
    }
}
