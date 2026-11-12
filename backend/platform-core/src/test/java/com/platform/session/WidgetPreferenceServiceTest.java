package com.platform.session;

import com.platform.auth.UserPrincipal;
import com.platform.domain.ConversationMemoryEntity;
import com.platform.domain.WidgetUserPreferenceEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.repository.ConversationMemoryRepository;
import com.platform.repository.WidgetUserPreferenceRepository;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-179 TC-CHT-050 ~ 052, 057: 기억과 선호 주인, 조회와 저장, 기억 목록과 지우기, 대화에 넣을 덩어리. */
@ExtendWith(MockitoExtension.class)
class WidgetPreferenceServiceTest {

    private static final String OWNER = "tester-a";
    private static final String USER_PROFILE = MemoryLayer.USER_PROFILE.name();
    private static final String PRIVATE = MemoryScope.PRIVATE.name();

    @Mock private WidgetUserPreferenceRepository preferenceRepository;
    @Mock private ConversationMemoryRepository memoryRepository;
    @Mock private ChatWidgetSettingsService widgetSettings;
    @Mock private PromptTemplateService promptTemplateService;

    private WidgetPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new WidgetPreferenceService(preferenceRepository, memoryRepository, widgetSettings, promptTemplateService);
        lenient().when(widgetSettings.memoryEnabled()).thenReturn(true);
        lenient().when(widgetSettings.preferencesMaxChars()).thenReturn(1500);
        lenient().when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("IT-12-008 1 user_ref_있는_위젯_토큰만_주인이다")
    void owner_is_widget_token_user_ref_only() {
        // TC-CHT-050
        assertThat(WidgetPreferenceService.ownerOf(widget(OWNER))).isEqualTo(OWNER);
        assertThat(WidgetPreferenceService.ownerOf(new UserPrincipal(OWNER, OWNER, "t1", "ADMIN", Map.of()))).isNull();
        assertThat(WidgetPreferenceService.ownerOf(null)).isNull();
        assertThat(WidgetPreferenceService.ownerOf(new UserPrincipal("widget", "widget", "t1", "WIDGET", Map.of()))).isNull();

        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "이번 질문"));
        assertThat(WidgetPreferenceService.memoryOwner(chat(OWNER, messages))).isEqualTo(OWNER);
        assertThat(WidgetPreferenceService.memoryOwner(chat(null, messages))).isNull();
        ChatRequest login = chat(null, messages).withWidgetTurn(new ChatRequest.WidgetTurn(OWNER, null, null, false));
        assertThat(WidgetPreferenceService.memoryOwner(login)).isNull();
    }

    @Test
    @DisplayName("IT-12-008 2 주인이_없으면_다섯_API_모두_memory_unavailable")
    void no_owner_is_forbidden() {
        // TC-CHT-050
        assertRejected(() -> service.preferences(null), HttpStatus.FORBIDDEN, "memory_unavailable");
        assertRejected(() -> service.updatePreferences(null, Map.of()), HttpStatus.FORBIDDEN, "memory_unavailable");
        assertRejected(() -> service.memories(null), HttpStatus.FORBIDDEN, "memory_unavailable");
        assertRejected(() -> service.deleteMemory(null, UUID.randomUUID().toString()), HttpStatus.FORBIDDEN, "memory_unavailable");
        assertRejected(() -> service.deleteAllMemories(null), HttpStatus.FORBIDDEN, "memory_unavailable");
    }

    @Test
    @DisplayName("IT-12-008 3 테넌트_스위치를_끄면_다섯_API_모두_memory_disabled")
    void tenant_switch_off_is_conflict() {
        // TC-CHT-050
        when(widgetSettings.memoryEnabled()).thenReturn(false);
        assertRejected(() -> service.preferences(OWNER), HttpStatus.CONFLICT, "memory_disabled");
        assertRejected(() -> service.updatePreferences(OWNER, Map.of()), HttpStatus.CONFLICT, "memory_disabled");
        assertRejected(() -> service.memories(OWNER), HttpStatus.CONFLICT, "memory_disabled");
        assertRejected(() -> service.deleteMemory(OWNER, UUID.randomUUID().toString()), HttpStatus.CONFLICT, "memory_disabled");
        assertRejected(() -> service.deleteAllMemories(OWNER), HttpStatus.CONFLICT, "memory_disabled");
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-12-001 1 행이_없으면_빈_선호와_자동_기억_켬")
    void no_row_returns_defaults() {
        // TC-CHT-051
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.empty());

        Map<String, Object> body = service.preferences(OWNER);

        assertThat(body).containsEntry("instructions", "").containsEntry("instructions_max_chars", 1500)
                .containsEntry("memory_enabled", true).containsEntry("last_model", null).containsEntry("last_thinking", null);
    }

    @Test
    @DisplayName("IT-12-001 2 내_선호만_저장하면_그_칸만_바뀌고_행이_생긴다")
    void saving_instructions_creates_row() {
        // TC-CHT-051
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.empty());

        Map<String, Object> body = service.updatePreferences(OWNER, Map.of("instructions", "  김 매니저라고 불러 주세요  "));

        ArgumentCaptor<WidgetUserPreferenceEntity> saved = ArgumentCaptor.forClass(WidgetUserPreferenceEntity.class);
        verify(preferenceRepository).save(saved.capture());
        assertThat(saved.getValue().getUserRef()).isEqualTo(OWNER);
        assertThat(saved.getValue().getInstructions()).isEqualTo("김 매니저라고 불러 주세요");
        assertThat(saved.getValue().isMemoryEnabled()).isTrue();
        assertThat(saved.getValue().getLastModel()).isNull();
        assertThat(body).containsEntry("instructions", "김 매니저라고 불러 주세요");
    }

    @Test
    @DisplayName("IT-12-009 1 모델과_깊게_생각하기만_저장하면_내_선호는_그대로다")
    void saving_last_values_keeps_instructions() {
        // TC-CHT-051
        WidgetUserPreferenceEntity row = row("답은 짧게");
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.of(row));

        Map<String, Object> body = service.updatePreferences(OWNER,
                Map.of("last_model", "claude-opus-5-5", "last_thinking", true));

        assertThat(row.getInstructions()).isEqualTo("답은 짧게");
        assertThat(row.getLastModel()).isEqualTo("claude-opus-5-5");
        assertThat(row.getLastThinking()).isTrue();
        assertThat(body).containsEntry("last_model", "claude-opus-5-5").containsEntry("last_thinking", true);
    }

    @Test
    @DisplayName("IT-12-010 1 글자_수를_넘으면_preferences_too_long_이고_저장하지_않는다")
    void too_long_instructions_rejected() {
        // TC-CHT-051
        WidgetUserPreferenceEntity row = row("앞 글");
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.of(row));

        assertRejected(() -> service.updatePreferences(OWNER, Map.of("instructions", "가".repeat(1501))),
                HttpStatus.BAD_REQUEST, "preferences_too_long");
        assertThat(row.getInstructions()).isEqualTo("앞 글");
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-12-001 2 빈_글로_저장하면_내_선호를_비운다")
    void blank_instructions_clears() {
        // TC-CHT-051
        WidgetUserPreferenceEntity row = row("앞 글");
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.of(row));
        Map<String, Object> body = new HashMap<>();
        body.put("instructions", "   ");

        service.updatePreferences(OWNER, body);

        assertThat(row.getInstructions()).isNull();
    }

    @Test
    @DisplayName("IT-12-009 1 모델_id_가_100자를_넘으면_model_invalid")
    void too_long_model_rejected() {
        // TC-CHT-051
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.of(row(null)));

        assertRejected(() -> service.updatePreferences(OWNER, Map.of("last_model", "m".repeat(101))),
                HttpStatus.BAD_REQUEST, "model_invalid");
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-12-006 1 기억_목록은_내_것만_오래된_것부터")
    void memories_are_own_oldest_first() {
        // TC-CHT-052 (남의 기억은 주인 조건으로 저장소가 거른다)
        ConversationMemoryEntity a = memory("정산 담당");
        ConversationMemoryEntity b = memory("표로 답을 원함");
        when(memoryRepository.findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(List.of(a, b));

        List<Map<String, Object>> list = service.memories(OWNER);

        assertThat(list).extracting(m -> m.get("content")).containsExactly("정산 담당", "표로 답을 원함");
        assertThat(list.get(0)).containsEntry("id", a.getId().toString()).containsKey("created_at");
    }

    @Test
    @DisplayName("IT-12-006 1 남의_기억이나_없는_id_를_지우면_memory_not_found")
    void deleting_other_memory_is_not_found() {
        // TC-CHT-052
        UUID other = UUID.randomUUID();
        when(memoryRepository.findByIdAndUserIdAndMemoryTypeAndScope(other, OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(Optional.empty());

        assertRejected(() -> service.deleteMemory(OWNER, other.toString()), HttpStatus.NOT_FOUND, "memory_not_found");
        assertRejected(() -> service.deleteMemory(OWNER, "not-a-uuid"), HttpStatus.NOT_FOUND, "memory_not_found");
        verify(memoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("IT-12-006 1 내_기억_하나를_지운다")
    void deleting_own_memory() {
        // TC-CHT-052
        ConversationMemoryEntity mine = memory("표로 답을 원함");
        when(memoryRepository.findByIdAndUserIdAndMemoryTypeAndScope(mine.getId(), OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(Optional.of(mine));

        service.deleteMemory(OWNER, mine.getId().toString());

        verify(memoryRepository).delete(mine);
    }

    @Test
    @DisplayName("IT-12-006 3 모두_지우기는_내_자동_기억만_지운다")
    void deleting_all_own_memories() {
        // TC-CHT-052
        when(memoryRepository.deleteByUserIdAndMemoryTypeAndScope(OWNER, USER_PROFILE, PRIVATE)).thenReturn(3L);

        assertThat(service.deleteAllMemories(OWNER)).isEqualTo(3L);
    }

    @Test
    @DisplayName("IT-12-001 3 내_선호와_기억을_틀_한_덩어리로_만든다")
    void context_block_has_instructions_and_memories() {
        // TC-CHT-057
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.of(row("김 매니저라고 불러 주세요")));
        when(memoryRepository.findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(List.of(memory("정산 담당"), memory("표로 답을 원함")));
        when(promptTemplateService.render(eq(WidgetPreferenceService.CONTEXT_TEMPLATE_KEY), anyMap())).thenReturn("block");

        assertThat(service.contextBlock(OWNER)).isEqualTo("block");
        verify(promptTemplateService).render(WidgetPreferenceService.CONTEXT_TEMPLATE_KEY, Map.of(
                "instructions", "김 매니저라고 불러 주세요",
                "memories", "- 정산 담당\n- 표로 답을 원함"));
    }

    @Test
    @DisplayName("IT-12-007 2 자동_기억을_끄면_내_선호만_넣는다")
    void context_block_without_memories_when_off() {
        // TC-CHT-057
        WidgetUserPreferenceEntity row = row("김 매니저라고 불러 주세요");
        row.setMemoryEnabled(false);
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.of(row));
        when(promptTemplateService.render(eq(WidgetPreferenceService.CONTEXT_TEMPLATE_KEY), anyMap())).thenReturn("block");

        service.contextBlock(OWNER);

        verify(memoryRepository, never()).findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(anyString(), anyString(), anyString());
        verify(promptTemplateService).render(WidgetPreferenceService.CONTEXT_TEMPLATE_KEY, Map.of(
                "instructions", "김 매니저라고 불러 주세요", "memories", WidgetPreferenceService.EMPTY_SLOT));
    }

    @Test
    @DisplayName("IT-12-003 3 내_선호가_비면_기억만_넣는다")
    void context_block_memories_only() {
        // TC-CHT-057
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.empty());
        when(memoryRepository.findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(List.of(memory("표로 답을 원함")));
        when(promptTemplateService.render(eq(WidgetPreferenceService.CONTEXT_TEMPLATE_KEY), anyMap())).thenReturn("block");

        service.contextBlock(OWNER);

        verify(promptTemplateService).render(WidgetPreferenceService.CONTEXT_TEMPLATE_KEY, Map.of(
                "instructions", WidgetPreferenceService.EMPTY_SLOT, "memories", "- 표로 답을 원함"));
    }

    @Test
    @DisplayName("IT-12-008 3 둘_다_없거나_스위치가_꺼졌거나_주인이_없으면_넣지_않는다")
    void context_block_absent() {
        // TC-CHT-057
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.empty());
        when(memoryRepository.findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(List.of());
        assertThat(service.contextBlock(OWNER)).isNull();
        assertThat(service.contextBlock(null)).isNull();

        when(widgetSettings.memoryEnabled()).thenReturn(false);
        assertThat(service.contextBlock(OWNER)).isNull();
        verify(promptTemplateService, never()).render(anyString(), anyMap());
    }

    private static UserPrincipal widget(String id) {
        return new UserPrincipal(id, id, "t1", "WIDGET", Map.of("user_ref", id));
    }

    private static WidgetUserPreferenceEntity row(String instructions) {
        WidgetUserPreferenceEntity row = new WidgetUserPreferenceEntity(OWNER);
        row.setInstructions(instructions);
        return row;
    }

    private static ConversationMemoryEntity memory(String content) {
        ConversationMemoryEntity m = new ConversationMemoryEntity();
        m.setId(UUID.randomUUID());
        m.setUserId(OWNER);
        m.setMemoryType(USER_PROFILE);
        m.setScope(PRIVATE);
        m.setContent(content);
        return m;
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage(code)
                .extracting("status").isEqualTo(status);
    }

    @Test
    @DisplayName("IT-12-002 1 기억_줄은_마지막_질문_바로_앞에_질문_몫_지시로_들어간다")
    void attach_puts_turn_scoped_block_before_last_question() {
        // TC-CHT-058
        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.of(row("표로 답해 주세요")));
        when(memoryRepository.findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(List.of());
        when(promptTemplateService.render(eq(WidgetPreferenceService.CONTEXT_TEMPLATE_KEY), anyMap())).thenReturn("block");
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "core"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "지난 질문"),
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "지난 답"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "이번 질문"));

        List<UnifiedMessage> out = service.attach(messages, chat(OWNER, messages));

        assertThat(out).hasSize(5);
        assertThat(out.get(3).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(out.get(3).turnScoped()).isTrue();
        assertThat(((ContentBlock.Text) out.get(3).content().get(0)).text()).isEqualTo("block");
        assertThat(out.get(4)).isSameAs(messages.get(3));
        assertThat(messages).hasSize(4);
    }

    @Test
    @DisplayName("IT-12-008 3 주인이_없거나_넣을_것이_없거나_읽기에_실패하면_그대로_둔다")
    void attach_leaves_messages_as_is() {
        // TC-CHT-057, TC-CHT-058
        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문"));

        assertThat(service.attach(messages, chat(null, messages))).isSameAs(messages);
        verifyNoInteractions(preferenceRepository);

        when(preferenceRepository.findById(OWNER)).thenReturn(Optional.empty());
        when(memoryRepository.findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(OWNER, USER_PROFILE, PRIVATE))
                .thenReturn(List.of());
        assertThat(service.attach(messages, chat(OWNER, messages))).isSameAs(messages);

        when(preferenceRepository.findById(OWNER)).thenThrow(new IllegalStateException("db down"));
        assertThat(service.attach(messages, chat(OWNER, messages))).isSameAs(messages);
    }

    private static ChatRequest chat(String owner, List<UnifiedMessage> messages) {
        ChatRequest request = new ChatRequest(null, "widget-s-1", messages,
                false, false, null, null, null, null, null, null, null, null);
        return owner == null ? request : request.withWidgetTurn(new ChatRequest.WidgetTurn(owner, null,
                Map.of("text", "이번 질문", "attachments", List.of()), true));
    }
}
