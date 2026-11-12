package com.platform.orchestrator;

import com.platform.domain.ChatProjectEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ChatProjectRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-CHT-048 (CR-178 BIZ-172): 프로젝트 지시문은 이번 질문 바로 앞에 질문마다 붙는 시스템 지시로 들어가고,
 * 대화 기록으로 넘어가는 목록은 건드리지 않는다. CLI 작업자 쪽 처리는 ClaudeCliWorkerTest 가 본다.
 */
@ExtendWith(MockitoExtension.class)
class TurnInstructionsTest {

    private static final String SESSION = "s-1";

    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private ChatProjectRepository projectRepository;
    @Mock private PromptTemplateService promptTemplateService;

    private TurnInstructions turnInstructions;

    @BeforeEach
    void setUp() {
        turnInstructions = new TurnInstructions(sessionRepository, projectRepository, promptTemplateService);
        lenient().when(promptTemplateService.render(eq(TurnInstructions.PROJECT_TEMPLATE_KEY), anyMap()))
                .thenAnswer(inv -> {
                    Map<String, Object> vars = inv.getArgument(1);
                    return "project " + vars.get("project_name") + ": " + vars.get("instructions");
                });
    }

    @Test
    void project_conversation_gets_instructions_right_before_the_question() {
        ChatProjectEntity project = project("보고서", "표로 답한다");
        sessionIn(project.getId());
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "지난 질문"),
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "지난 답"),
                UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "screen context").asTurnScoped(),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "이번 질문"));

        List<UnifiedMessage> out = turnInstructions.attach(messages, SESSION, request(null));

        assertThat(out).hasSize(5);
        UnifiedMessage inserted = out.get(3);
        assertThat(inserted.role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(inserted.turnScoped()).isTrue();
        assertThat(text(inserted)).isEqualTo("project 보고서: 표로 답한다");
        assertThat(text(out.get(4))).isEqualTo("이번 질문");
        assertThat(messages).hasSize(4);
    }

    @Test
    void conversation_outside_projects_is_left_as_is() {
        sessionIn(null);
        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문"));

        assertThat(turnInstructions.attach(messages, SESSION, request(null))).isSameAs(messages);
        verify(promptTemplateService, never()).render(any(), anyMap());
    }

    @Test
    void edited_instructions_apply_from_the_next_question() {
        ChatProjectEntity project = project("보고서", "첫 지시");
        sessionIn(project.getId());
        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문"));
        turnInstructions.attach(messages, SESSION, request(null));

        project.setInstructions("고친 지시");
        List<UnifiedMessage> out = turnInstructions.attach(messages, SESSION, request(null));

        assertThat(text(out.get(0))).isEqualTo("project 보고서: 고친 지시");
    }

    @Test
    void first_question_uses_the_project_carried_by_the_request() {
        ChatProjectEntity project = project("보고서", "표로 답한다");
        when(sessionRepository.findBySessionId(SESSION)).thenReturn(Optional.empty());
        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "첫 질문"));

        List<UnifiedMessage> out = turnInstructions.attach(messages, SESSION, request(project.getId()));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).turnScoped()).isTrue();
    }

    @Test
    void project_without_instructions_adds_nothing() {
        ChatProjectEntity project = project("보고서", null);
        sessionIn(project.getId());
        List<UnifiedMessage> messages = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문"));

        assertThat(turnInstructions.attach(messages, SESSION, request(null))).isSameAs(messages);
    }

    private ChatProjectEntity project(String name, String instructions) {
        ChatProjectEntity p = new ChatProjectEntity();
        p.setId(UUID.randomUUID());
        p.setOwnerRef("user-a");
        p.setName(name);
        p.setInstructions(instructions);
        lenient().when(projectRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private void sessionIn(UUID projectId) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setSessionId(SESSION);
        s.setUserId("user-a");
        s.setChatProjectId(projectId);
        when(sessionRepository.findBySessionId(SESSION)).thenReturn(Optional.of(s));
    }

    private static ChatRequest request(UUID chatProjectId) {
        ChatRequest base = new ChatRequest("claude-sonnet-5-5", List.of());
        return chatProjectId == null ? base
                : base.withWidgetTurn(new ChatRequest.WidgetTurn("user-a", chatProjectId, null, true));
    }

    private static String text(UnifiedMessage m) {
        return ((ContentBlock.Text) m.content().get(0)).text();
    }
}
