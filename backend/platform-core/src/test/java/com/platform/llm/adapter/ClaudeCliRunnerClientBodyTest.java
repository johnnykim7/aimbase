package com.platform.llm.adapter;

import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.SkillRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    /** CR-175 TC-CHT-017: 생각 설정이 있을 때만 실행기에 싣는다. */
    @Test
    void thinking_is_sent_only_when_someone_set_it() {
        assertThat(ClaudeCliRunnerClient.buildBody(request(), "AIMBASE", null, null, true, null))
                .doesNotContainKey("thinking");
        assertThat(ClaudeCliRunnerClient.buildBody(withConfig(ModelConfig.defaults().withThinkingOverride(true)),
                "AIMBASE", null, null, true, null).get("thinking")).isEqualTo(true);
        assertThat(ClaudeCliRunnerClient.buildBody(withConfig(ModelConfig.defaults().withThinkingOverride(false)),
                "AIMBASE", null, null, true, null).get("thinking")).isEqualTo(false);
    }

    /** CR-175 TC-CHT-017: 실행기 생각 이벤트는 생각 조각이 된다. */
    @Test
    void runner_thinking_event_becomes_thinking_chunk() {
        List<LLMStreamChunk> chunks = new ArrayList<>();
        ClaudeCliRunnerClient.handleStreamEvent(Map.of("type", "thinking", "delta", "생각 글"), "run-1", "m", chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).type()).isEqualTo("thinking");
        assertThat(chunks.get(0).delta()).isEqualTo("생각 글");
    }

    private static LLMRequest withConfig(ModelConfig config) {
        return new LLMRequest("claude-sonnet-4-5", List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")),
                null, config, true, "run-1");
    }
}
