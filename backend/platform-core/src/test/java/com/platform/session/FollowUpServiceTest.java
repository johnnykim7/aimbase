package com.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.TokenUsage;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.repository.UsageLogRepository;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-175 TC-CHT-012 ~ 014: 후속 질문과 대화 제목. */
@ExtendWith(MockitoExtension.class)
class FollowUpServiceTest {

    @Mock private ChatWidgetSettingsService widgetSettings;
    @Mock private ChatTurnLookup turnLookup;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private LLMAdapterRegistry adapterRegistry;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private UsageLogRepository usageLogRepository;
    @Mock private LLMAdapter adapter;

    private FollowUpService service;
    private final UUID turnId = UUID.randomUUID();
    private ConversationSessionEntity session;

    @BeforeEach
    void setUp() {
        service = new FollowUpService(widgetSettings, turnLookup, promptTemplateService, connectionAdapterFactory,
                adapterRegistry, messageRepository, sessionRepository, usageLogRepository, new ObjectMapper());
        session = new ConversationSessionEntity();
        session.setSessionId("s-1");
        session.setTitle("박스가 몇 개 나갔나요?");
        lenient().when(widgetSettings.followUpsEnabled()).thenReturn(true);
        lenient().when(widgetSettings.followUpsCount()).thenReturn(3);
        lenient().when(widgetSettings.followUpsTimeoutMs()).thenReturn(2000L);
        lenient().when(turnLookup.find("user-1", "s-1", turnId.toString())).thenReturn(turn());
        lenient().when(promptTemplateService.render(eq(FollowUpService.TEMPLATE_KEY), anyMap())).thenReturn("prompt");
        lenient().when(connectionAdapterFactory.defaultModel("conn-1")).thenReturn("claude-sonnet-5-5");
        lenient().when(connectionAdapterFactory.getAdapter("conn-1")).thenReturn(adapter);
    }

    @Test
    @DisplayName("IT-09-013 6 후속_질문은_별도_세션으로_묻고_대화_기록에_더하지_않는다")
    void first_answer_gets_follow_ups_and_generated_title() {
        // TC-CHT-012
        when(messageRepository.existsBySessionIdAndRoleAndSeqLessThanAndDeletedAtIsNull("s-1", "assistant", 4))
                .thenReturn(false);
        when(adapter.chat(any())).thenReturn(reply(
                "```json\n{\"follow_ups\": [\"몇 박스 출고?\", \"택배사는?\", \"답변 초안\", \"넷째\"], \"title\": \"13박스 누락 문의\"}\n```"));

        Map<String, Object> body = service.generate("user-1", "s-1", turnId.toString(), "conn-1");

        assertThat(body.get("follow_ups")).isEqualTo(List.of("몇 박스 출고?", "택배사는?", "답변 초안"));
        assertThat(body.get("title")).isEqualTo("13박스 누락 문의");
        assertThat(session.getTitle()).isEqualTo("13박스 누락 문의");
        assertThat(session.getTitleSource()).isEqualTo(ConversationSessionEntity.TITLE_SOURCE_GENERATED);
        verify(sessionRepository).save(session);

        ArgumentCaptor<LLMRequest> request = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(request.capture());
        assertThat(request.getValue().sessionId()).isNotEqualTo("s-1").startsWith("s-1:follow-ups:");
        assertThat(request.getValue().tools()).isNull();
        verify(adapter).cleanupSession(request.getValue().sessionId());
        verify(usageLogRepository).save(any());
        verify(messageRepository, never()).save(any());
        verify(messageRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("IT-09-013 5 사람이_고친_제목은_그대로다")
    void title_fixed_by_person_is_kept() {
        // TC-CHT-013
        session.setTitleSource(ConversationSessionEntity.TITLE_SOURCE_USER);
        when(messageRepository.existsBySessionIdAndRoleAndSeqLessThanAndDeletedAtIsNull("s-1", "assistant", 4))
                .thenReturn(false);
        when(adapter.chat(any())).thenReturn(reply("{\"follow_ups\": [\"하나\"], \"title\": \"\"}"));

        Map<String, Object> body = service.generate("user-1", "s-1", turnId.toString(), "conn-1");

        assertThat(body).doesNotContainKey("title");
        assertThat(session.getTitle()).isEqualTo("박스가 몇 개 나갔나요?");
        verify(sessionRepository, never()).save(any());
        verify(promptTemplateService).render(eq(FollowUpService.TEMPLATE_KEY),
                eq(Map.of("count", 3, "need_title", "no", "question", "박스가 몇 개 나갔나요?", "answer", "12박스입니다.")));
    }

    @Test
    @DisplayName("IT-09-014 2 후속_질문을_끄면_follow_ups_disabled")
    void disabled_setting_is_conflict() {
        // TC-CHT-014
        when(widgetSettings.followUpsEnabled()).thenReturn(false);

        assertRejected(() -> service.generate("user-1", "s-1", turnId.toString(), "conn-1"),
                HttpStatus.CONFLICT, "follow_ups_disabled");
    }

    @Test
    @DisplayName("IT-09-014 3 JSON_이_아닌_답은_follow_ups_failed")
    void non_json_reply_is_bad_gateway() {
        // TC-CHT-014
        when(messageRepository.existsBySessionIdAndRoleAndSeqLessThanAndDeletedAtIsNull("s-1", "assistant", 4))
                .thenReturn(true);
        when(adapter.chat(any())).thenReturn(reply("질문을 세 개 드릴게요"));

        assertRejected(() -> service.generate("user-1", "s-1", turnId.toString(), "conn-1"),
                HttpStatus.BAD_GATEWAY, "follow_ups_failed");
    }

    @Test
    @DisplayName("IT-09-014 3 늦은_답은_시간_한도에서_끊고_follow_ups_failed")
    void slow_reply_is_cut_at_timeout_and_session_is_cleaned() {
        // TC-CHT-014
        when(widgetSettings.followUpsTimeoutMs()).thenReturn(50L);
        when(messageRepository.existsBySessionIdAndRoleAndSeqLessThanAndDeletedAtIsNull("s-1", "assistant", 4))
                .thenReturn(true);
        when(adapter.chat(any())).thenAnswer(inv -> {
            Thread.sleep(1000);
            return reply("{}").join();
        });

        assertRejected(() -> service.generate("user-1", "s-1", turnId.toString(), "conn-1"),
                HttpStatus.BAD_GATEWAY, "follow_ups_failed");
        verify(adapter).cleanupSession("s-1:follow-ups:" + turnId);
    }

    private ChatTurnLookup.Turn turn() {
        ConversationMessageEntity question = new ConversationMessageEntity();
        question.setSeq(4);
        question.setRole("user");
        question.setContent("박스가 몇 개 나갔나요?");
        question.setTurnId(turnId);
        ConversationMessageEntity answer = new ConversationMessageEntity();
        answer.setSeq(5);
        answer.setRole("assistant");
        answer.setContent("12박스입니다.");
        answer.setTurnId(turnId);
        return new ChatTurnLookup.Turn(session, turnId, List.of(question, answer));
    }

    private static CompletableFuture<LLMResponse> reply(String text) {
        return CompletableFuture.completedFuture(new LLMResponse("r-1", "claude-sonnet-5-5",
                List.of(new ContentBlock.Text(text)), List.of(), new TokenUsage(10, 20),
                LLMResponse.FinishReason.END, 5L, 0.001));
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage(code)
                .extracting("status").isEqualTo(status);
    }
}
