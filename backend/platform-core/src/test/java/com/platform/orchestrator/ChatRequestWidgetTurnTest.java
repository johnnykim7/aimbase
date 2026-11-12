package com.platform.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-179 TC-CHT-053, 054: 대화 요청의 위젯 값에서 읽는 기억 주인과 질문 글. */
class ChatRequestWidgetTurnTest {

    @Test
    @DisplayName("IT-12-003 1 질문_글만_읽고_첨부는_읽지_않는다")
    void question_text_only() {
        Map<String, Object> display = Map.of(
                "text", "앞으로 답은 표로 정리해 주세요",
                "attachments", List.of(Map.of("attachment_id", "att-1", "filename", "a.pdf", "media_type", "application/pdf")));

        assertThat(new ChatRequest.WidgetTurn("tester-a", null, display, true).questionText())
                .isEqualTo("앞으로 답은 표로 정리해 주세요");
        assertThat(new ChatRequest.WidgetTurn("tester-a", null, null, true).questionText()).isNull();
    }

    @Test
    @DisplayName("IT-12-008 2 user_ref_없는_위젯_토큰과_로그인_사용자는_기억_주인이_없다")
    void memory_owner_is_widget_user_ref_only() {
        assertThat(new ChatRequest.WidgetTurn("tester-a", null, null, true).memoryOwner()).isEqualTo("tester-a");
        assertThat(new ChatRequest.WidgetTurn(null, null, null, true).memoryOwner()).isNull();
        assertThat(new ChatRequest.WidgetTurn("user-1", null, null, false).memoryOwner()).isNull();
    }
}
