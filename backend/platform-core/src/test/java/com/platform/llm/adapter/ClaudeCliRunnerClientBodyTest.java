package com.platform.llm.adapter;

import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.SkillRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-147 단계가 고정한 스킬과 판이 러너 요청 본문에 실리는지. TC-SKB-024.
 */
class ClaudeCliRunnerClientBodyTest {

    private static LLMRequest request() {
        return new LLMRequest("claude-sonnet-4-5", List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));
    }

    @Test
    void pinned_skills_are_sent_as_skill_set() {
        LLMRequest req = request().withSkillSet(List.of(new SkillRef("ax-proposal", "tenant", 1)));

        Map<String, Object> body = ClaudeCliRunnerClient.buildBody(req, "NATIVE", null, null, true, null);

        assertThat(body.get("skill_set"))
                .isEqualTo(List.of(Map.of("name", "ax-proposal", "scope", "tenant", "version", 1)));
    }

    @Test
    void no_pins_sends_no_skill_set() {
        assertThat(ClaudeCliRunnerClient.buildBody(request(), "AIMBASE", null, null, true, null))
                .doesNotContainKey("skill_set");
        assertThat(ClaudeCliRunnerClient.buildBody(request().withSkillSet(List.of()), "AIMBASE", null, null, true, null))
                .doesNotContainKey("skill_set");
    }
}
