package com.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatRequest;
import com.platform.repository.ConversationMemoryRepository;
import com.platform.repository.UsageLogRepository;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CR-179 TC-CHT-053 ~ 056: 위젯 자동 기억 뽑기. */
@ExtendWith(MockitoExtension.class)
class MemoryAutoExtractServiceTest {

    private static final String OWNER = "tester-a";

    @Mock private ChatWidgetSettingsService widgetSettings;
    @Mock private WidgetPreferenceService preferenceService;
    @Mock private ConversationMemoryRepository memoryRepository;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private LLMAdapterRegistry adapterRegistry;
    @Mock private UsageLogRepository usageLogRepository;
    @Mock private LLMAdapter adapter;

    private MemoryAutoExtractService service;
    private final UUID turnId = UUID.randomUUID();
    private List<ConversationMemoryEntity> stored;

    @BeforeEach
    void setUp() {
        service = new MemoryAutoExtractService(widgetSettings, preferenceService, memoryRepository, promptTemplateService,
                connectionAdapterFactory, adapterRegistry, usageLogRepository, new ObjectMapper());
        stored = new ArrayList<>();
        lenient().when(widgetSettings.memoryEnabled()).thenReturn(true);
        lenient().when(widgetSettings.memoryTimeoutMs()).thenReturn(2000L);
        lenient().when(widgetSettings.memoryMaxItems()).thenReturn(30);
        lenient().when(widgetSettings.memoryItemMaxChars()).thenReturn(200);
        lenient().when(preferenceService.autoMemoryOn(OWNER)).thenReturn(true);
        lenient().when(preferenceService.findMemories(OWNER)).thenAnswer(inv -> new ArrayList<>(stored));
        lenient().when(promptTemplateService.render(eq(MemoryAutoExtractService.TEMPLATE_KEY), anyMap())).thenReturn("prompt");
        lenient().when(connectionAdapterFactory.defaultModel("conn-1")).thenReturn("claude-sonnet-5-5");
        lenient().when(connectionAdapterFactory.getAdapter("conn-1")).thenReturn(adapter);
        lenient().when(memoryRepository.save(any())).thenAnswer(inv -> {
            ConversationMemoryEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    @DisplayName("IT-12-008 2 주인이_없거나_질문_글이_없거나_스위치가_꺼지면_뽑지_않는다")
    void no_owner_or_switch_off_skips() {
        // TC-CHT-053
        service.extractAfterTurn(new ChatRequest("claude-sonnet-5-5", List.of()), "s-1", turnId);
        service.extractAfterTurn(request(null, "질문", true), "s-1", turnId);
        service.extractAfterTurn(request(OWNER, "질문", false), "s-1", turnId);
        service.extractAfterTurn(request(OWNER, "  ", true), "s-1", turnId);
        service.extractAfterTurn(request(OWNER, null, true), "s-1", turnId);
        when(widgetSettings.memoryEnabled()).thenReturn(false);
        service.extractAfterTurn(request(OWNER, "답은 표로 주세요", true), "s-1", turnId);

        verifyNoInteractions(preferenceService, adapter, connectionAdapterFactory);
    }

    @Test
    @DisplayName("IT-12-007 3 그_사람_자동_기억이_꺼져_있으면_부르지_않는다")
    void auto_memory_off_skips_call() {
        // TC-CHT-053
        when(preferenceService.autoMemoryOn(OWNER)).thenReturn(false);

        service.extract(OWNER, "저는 야간 근무자예요", "s-1", turnId, "conn-1");

        verifyNoInteractions(adapter);
        verify(memoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-12-003 1 질문_글과_지금_기억만_틀에_넣고_별도_세션으로_부른다")
    void call_shape() {
        // TC-CHT-054
        stored.add(memory("정산 담당", 1));
        when(adapter.chat(any())).thenReturn(reply("{\"add\": [], \"remove\": []}"));

        service.extract(OWNER, "앞으로 답은 표로 정리해 주세요", "s-1", turnId, "conn-1");

        verify(promptTemplateService).render(MemoryAutoExtractService.TEMPLATE_KEY, Map.of(
                "question", "앞으로 답은 표로 정리해 주세요",
                "memories", stored.get(0).getId() + ": 정산 담당",
                "max_chars", 200));
        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(sent.capture());
        assertThat(sent.getValue().model()).isEqualTo("claude-sonnet-5-5");
        assertThat(sent.getValue().sessionId()).isEqualTo("s-1:memory:" + turnId);
        assertThat(sent.getValue().tools()).isNull();
        assertThat(sent.getValue().config().explicitThinking()).isFalse();
        assertThat(sent.getValue().messages()).hasSize(1);
        verify(adapter).cleanupSession("s-1:memory:" + turnId);
        verify(usageLogRepository).save(any());
    }

    @Test
    @DisplayName("IT-12-003 1 설정_모델이_있으면_그_모델로_부른다")
    void configured_model_wins() {
        // TC-CHT-054
        when(widgetSettings.memoryModel()).thenReturn("claude-haiku-5-5");
        when(adapter.chat(any())).thenReturn(reply("{\"add\": []}"));

        service.extract(OWNER, "답은 짧게 주세요", "s-1", turnId, "conn-1");

        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(sent.capture());
        assertThat(sent.getValue().model()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    @DisplayName("IT-12-003 2 뽑은_기억을_그_사람_자동_기억으로_넣는다")
    void add_saves_user_profile_memories() {
        // TC-CHT-055
        service.apply(OWNER, new MemoryAutoExtractService.Changes(List.of("3PL 정산 담당", "답은 표로 받기를 원함"), Set.of()));

        ArgumentCaptor<ConversationMemoryEntity> saved = ArgumentCaptor.forClass(ConversationMemoryEntity.class);
        verify(memoryRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ConversationMemoryEntity::getContent)
                .containsExactly("3PL 정산 담당", "답은 표로 받기를 원함");
        assertThat(saved.getAllValues()).allSatisfy(m -> {
            assertThat(m.getUserId()).isEqualTo(OWNER);
            assertThat(m.getMemoryType()).isEqualTo(MemoryLayer.USER_PROFILE.name());
            assertThat(m.getScope()).isEqualTo(MemoryScope.PRIVATE.name());
            assertThat(m.getSessionId()).isNull();
        });
    }

    @Test
    @DisplayName("IT-12-003 2 같은_글은_넣지_않고_긴_글은_자른다")
    void duplicate_skipped_and_long_cut() {
        // TC-CHT-055
        stored.add(memory("답은 표로 받기를 원함", 1));

        service.apply(OWNER, new MemoryAutoExtractService.Changes(
                List.of("답은  표로 받기를 원함 ", "가".repeat(250)), Set.of()));

        ArgumentCaptor<ConversationMemoryEntity> saved = ArgumentCaptor.forClass(ConversationMemoryEntity.class);
        verify(memoryRepository).save(saved.capture());
        assertThat(saved.getValue().getContent()).hasSize(200);
    }

    @Test
    @DisplayName("IT-12-005 2 뒤집은_기억은_내_것만_지운다")
    void remove_only_own_ids() {
        // TC-CHT-055
        ConversationMemoryEntity shortAnswer = memory("답은 짧게 받기를 원함", 1);
        stored.add(shortAnswer);
        String othersId = UUID.randomUUID().toString();

        service.apply(OWNER, new MemoryAutoExtractService.Changes(
                List.of("답은 자세하게 받기를 원함"), Set.of(shortAnswer.getId().toString(), othersId)));

        verify(memoryRepository).deleteAll(List.of(shortAnswer));
        verify(memoryRepository).save(any());
    }

    @Test
    @DisplayName("IT-12-004 2 JSON_이_아닌_응답이면_아무것도_바꾸지_않는다")
    void non_json_reply_changes_nothing() {
        // TC-CHT-055
        when(adapter.chat(any())).thenReturn(reply("기억할 것이 없습니다"));

        service.extract(OWNER, "홍길동 고객 주문 20261113-0042 답변 초안", "s-1", turnId, "conn-1");

        verify(memoryRepository, never()).save(any());
        verify(memoryRepository, never()).deleteAll(anyList());
        assertThat(service.parse("```json\n{\"add\": [\"표로 답\"], \"remove\": [1]}\n```"))
                .isEqualTo(new MemoryAutoExtractService.Changes(List.of("표로 답"), Set.of()));
    }

    @Test
    @DisplayName("IT-12-003 2 개수_상한을_넘으면_가장_오래된_것부터_지운다")
    void cap_drops_oldest() {
        // TC-CHT-056
        for (int i = 0; i < 29; i++) stored.add(memory("기억 " + i, 100 - i));

        service.apply(OWNER, new MemoryAutoExtractService.Changes(List.of("새 1", "새 2", "새 3"), Set.of()));

        verify(memoryRepository, times(3)).save(any());
        verify(memoryRepository).deleteAll(List.of(stored.get(0), stored.get(1)));
    }

    @Test
    @DisplayName("IT-12-003 1 호출이_시간을_넘기거나_실패하면_기억을_바꾸지_않는다")
    void timeout_or_failure_is_isolated() {
        // TC-CHT-056
        when(widgetSettings.memoryTimeoutMs()).thenReturn(50L);
        when(adapter.chat(any())).thenReturn(new CompletableFuture<>());
        service.extract(OWNER, "답은 표로 주세요", "s-1", turnId, "conn-1");

        when(adapter.chat(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
        service.extract(OWNER, "답은 표로 주세요", "s-1", turnId, "conn-1");

        verify(memoryRepository, never()).save(any());
        verify(usageLogRepository, never()).save(any());
        verify(adapter, times(2)).cleanupSession("s-1:memory:" + turnId);
    }

    @Test
    void normalize_ignores_spacing_and_case() {
        assertThat(MemoryAutoExtractService.normalize("  Prefers  TABLES ")).isEqualTo("prefers tables");
        assertThat(MemoryAutoExtractService.normalize(null)).isEmpty();
        verify(memoryRepository, never()).save(any());
        verify(promptTemplateService, never()).render(anyString(), anyMap());
    }

    /** widget 거짓은 로그인 사용자 요청이다. question 이 null 이면 세션 없는 요청처럼 질문 표시 값이 없다. */
    private static ChatRequest request(String owner, String question, boolean widget) {
        Map<String, Object> display = question == null ? null : Map.of("text", question, "attachments", List.of());
        return new ChatRequest("claude-sonnet-5-5", List.of())
                .withWidgetTurn(new ChatRequest.WidgetTurn(owner, null, display, widget));
    }

    private static ConversationMemoryEntity memory(String content, int minutesAgo) {
        ConversationMemoryEntity m = mock(ConversationMemoryEntity.class);
        UUID id = UUID.randomUUID();
        lenient().when(m.getId()).thenReturn(id);
        lenient().when(m.getContent()).thenReturn(content);
        lenient().when(m.getCreatedAt()).thenReturn(OffsetDateTime.now().minusMinutes(minutesAgo));
        return m;
    }

    private static CompletableFuture<LLMResponse> reply(String text) {
        return CompletableFuture.completedFuture(new LLMResponse("r-1", "claude-sonnet-5-5",
                List.of(new ContentBlock.Text(text)), List.of(), new TokenUsage(10, 20),
                LLMResponse.FinishReason.END, 5L, 0.001));
    }
}
