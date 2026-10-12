package com.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-147 단계 config.skills 저장 검사. TC-SKB-025.
 */
class WorkflowValidatorSkillsTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    private void validate(String type, Map<String, Object> config) {
        validator.validate(List.of(Map.of("id", "s1", "type", type, "config", config)));
    }

    private void rejected(String type, Map<String, Object> config, String message) {
        assertThatThrownBy(() -> validate(type, config))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(message);
    }

    @Test
    void TC_SKB_025_bad_shapes_are_rejected() {
        rejected("LLM_CALL", Map.of("skills", Map.of("name", "demo")), "config.skills must be an array");
        rejected("LLM_CALL", Map.of("skills", List.of("demo")), "config.skills[0] must be an object");
        rejected("AGENT_CALL", Map.of("skills", List.of(Map.of("name", " "))), "config.skills[0].name must not be empty");
        rejected("AGENT_CALL", Map.of("skills", List.of(Map.of("name", "demo", "files", "a.md"))),
                "config.skills[0].files must be an array of file paths");
        rejected("AGENT_CALL", Map.of("skills", List.of(Map.of("name", "demo", "version", 0))),
                "config.skills[0].version must be a positive integer");
        rejected("AGENT_CALL", Map.of("skills", List.of(Map.of("name", "demo", "version", 1.5))),
                "config.skills[0].version must be a positive integer");
        rejected("AGENT_CALL", Map.of("skills", List.of(Map.of("name", "demo"), Map.of("name", "demo"))),
                "more than once");
    }

    @Test
    void agents_and_foreach_body_are_checked() {
        rejected("AGENT_CALL", Map.of("agents", List.of(Map.of("prompt", "a", "skills", List.of(Map.of())))),
                "config.agents[0].skills[0].name must not be empty");
        rejected("FOREACH", Map.of("items", "{{input.list}}", "body",
                        Map.of("type", "LLM_CALL", "config", Map.of("skills", "demo"))),
                "config.skills must be an array");
    }

    @Test
    void valid_and_unrelated_steps_pass() {
        assertThatCode(() -> validate("AGENT_CALL", Map.of("prompt", "p", "skills", List.of(
                Map.of("name", "ax-proposal", "version", 3, "files", List.of("references/01.md")),
                Map.of("name", "sam-gov-review"))))).doesNotThrowAnyException();
        assertThatCode(() -> validate("TOOL_CALL", Map.of("skills", "ignored"))).doesNotThrowAnyException();
    }
}
