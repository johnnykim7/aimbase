package com.platform.llm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-CHT-048 (CR-178 BIZ-172): 이번 질문 몫 표시는 대화 기록(Redis, DB)에 남지 않는다. 남으면 다음 질문에서
 * 지난 화면 맥락까지 다시 붙는다.
 */
class UnifiedMessageTurnScopedTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void turn_scoped_mark_is_not_serialized() throws Exception {
        UnifiedMessage marked = UnifiedMessage.turnScopedSystem("project instructions");

        String json = mapper.writeValueAsString(marked);
        UnifiedMessage restored = mapper.readValue(json, UnifiedMessage.class);

        assertThat(json).doesNotContain("turnScoped").doesNotContain("turn_scoped");
        assertThat(restored.turnScoped()).isFalse();
        assertThat(restored.role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
    }

    @Test
    void only_system_messages_take_the_mark() {
        UnifiedMessage user = UnifiedMessage.ofText(UnifiedMessage.Role.USER, "question");
        UnifiedMessage system = UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "screen");

        assertThat(user.asTurnScoped()).isSameAs(user);
        assertThat(system.asTurnScoped().turnScoped()).isTrue();
        assertThat(system.turnScoped()).isFalse();
        assertThat(new UnifiedMessage(UnifiedMessage.Role.SYSTEM, List.of(new ContentBlock.Text("x"))).turnScoped())
                .isFalse();
    }
}
