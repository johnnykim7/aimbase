package com.platform.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("단계 틀 변수 {{run.*}} (CR-174, PRD-365)")
class StepContextRunVarsTest {

    private final StepContext ctx = new StepContext("run-1", "wf-1", "sess-1",
            Map.of("change_id", "ord-1"), Map.of());

    @Test
    @DisplayName("TC-WF-041 {{run.id}}, {{run.workflow_id}}, {{run.session_id}} 가 run 값으로 풀린다")
    void resolvesRunVariables() {
        assertThat(ctx.resolve("{{run.id}}")).isEqualTo("run-1");
        assertThat(ctx.resolve("{{run.workflow_id}}/{{run.session_id}}")).isEqualTo("wf-1/sess-1");
        assertThat(ctx.resolve("승인 대기 {{input.change_id}} run {{run.id}}")).isEqualTo("승인 대기 ord-1 run run-1");
        assertThat(ctx.resolveObject("{{run.id}}")).isEqualTo("run-1");
        assertThat(ctx.resolveMap(Map.of("body", Map.of("run_id", "{{run.id}}"))))
                .containsEntry("body", Map.of("run_id", "run-1"));
    }

    @Test
    @DisplayName("TC-WF-041 모르는 run 키와 세션 없는 run 은 빈 글이다")
    void unknownKeyIsEmpty() {
        StepContext noSession = new StepContext("run-2", "wf-1", null, Map.of(), Map.of());

        assertThat(ctx.resolve("{{run.nope}}")).isEmpty();
        assertThat(noSession.resolve("{{run.session_id}}")).isEmpty();
    }
}
