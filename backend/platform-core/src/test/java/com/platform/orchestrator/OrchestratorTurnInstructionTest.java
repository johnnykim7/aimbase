package com.platform.orchestrator;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-181 T3-5 TC-CHT-076: 고치기 지시는 이번 요청의 마지막 질문에만 붙고 원래 목록은 그대로 둔다. */
class OrchestratorTurnInstructionTest {

    @Test
    void prefixesLastUserTextOnly() {
        List<UnifiedMessage> history = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "first"),
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "answer"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "make it red"));

        List<UnifiedMessage> out = OrchestratorEngine.withTurnInstruction(history, "EDIT");

        assertThat(text(out.get(2))).isEqualTo("EDIT\n\nmake it red");
        assertThat(text(out.get(0))).isEqualTo("first");
        assertThat(text(history.get(2))).isEqualTo("make it red");
    }

    @Test
    void addsTextWhenLastUserHasNone() {
        List<UnifiedMessage> history = List.of(new UnifiedMessage(UnifiedMessage.Role.USER, List.of()));

        List<UnifiedMessage> out = OrchestratorEngine.withTurnInstruction(history, "EDIT");

        assertThat(text(out.get(0))).isEqualTo("EDIT");
    }

    @Test
    void blankInstructionKeepsSameList() {
        List<UnifiedMessage> history = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "q"));

        assertThat(OrchestratorEngine.withTurnInstruction(history, " ")).isSameAs(history);
        assertThat(OrchestratorEngine.withTurnInstruction(history, null)).isSameAs(history);
    }

    private static String text(UnifiedMessage m) {
        return ((ContentBlock.Text) m.content().get(0)).text();
    }
}
