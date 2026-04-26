package com.platform.tool;

import com.platform.llm.model.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-068: ToolCallHandler 의 ThreadLocal action tracking 검증.
 *
 * <p>OrchestratorEngine 이 호출 전 beginActionTracking() → executeLoop 안에서 도구 호출 시 누적 →
 * 호출 후 drainActionTracking() 으로 회수해 ChatResponse.actions_executed 에 흘리는 흐름.
 * 이전에는 OrchestratorEngine 이 List.of() 하드코딩으로 늘 빈 리스트를 반환했다.
 */
class ToolCallHandlerActionTrackingTest {

    @AfterEach
    void cleanup() {
        // 테스트 간 ThreadLocal 누수 방지
        ToolCallHandler.drainActionTracking();
    }

    @Test
    @DisplayName("begin 없이 drain 하면 빈 리스트 반환")
    void drain_without_begin_returns_empty() {
        assertThat(ToolCallHandler.drainActionTracking()).isEmpty();
    }

    @Test
    @DisplayName("begin → drain 사이에 tool 호출 누적 없으면 빈 리스트")
    void begin_drain_no_tools_returns_empty() {
        ToolCallHandler.beginActionTracking();
        assertThat(ToolCallHandler.drainActionTracking()).isEmpty();
    }

    @Test
    @DisplayName("drain 후 ThreadLocal 정리되어 다음 호출은 빈 리스트")
    void drain_clears_threadlocal() {
        ToolCallHandler.beginActionTracking();
        ToolCallHandler.drainActionTracking();
        // 두 번째 drain 은 begin 안 했으니 빈 리스트
        assertThat(ToolCallHandler.drainActionTracking()).isEmpty();
    }

    @Test
    @DisplayName("begin → 도구 호출 시뮬레이션 (private recordAction 효과 — drain 으로 결과만 검증)")
    void begin_drain_with_action_recorded() throws Exception {
        ToolCallHandler.beginActionTracking();
        // recordAction 은 private 라 reflection 으로 호출 (실제 사용은 ToolCallHandler.executeLoop 내부)
        var m = ToolCallHandler.class.getDeclaredMethod("recordAction", ToolCall.class);
        m.setAccessible(true);
        m.invoke(null, new ToolCall("call-1", "builtin_glob", Map.of("pattern", "**/*.java")));
        m.invoke(null, new ToolCall("call-2", "builtin_file_read", Map.of("file_path", "/tmp/x.java")));

        List<Map<String, Object>> actions = ToolCallHandler.drainActionTracking();
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0)).containsEntry("name", "builtin_glob");
        assertThat(actions.get(0)).extractingByKey("input").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("pattern", "**/*.java");
        assertThat(actions.get(1)).containsEntry("name", "builtin_file_read");
    }

    @Test
    @DisplayName("input null 인 ToolCall 도 빈 Map 으로 정규화되어 누적")
    void null_input_normalized_to_empty_map() throws Exception {
        ToolCallHandler.beginActionTracking();
        var m = ToolCallHandler.class.getDeclaredMethod("recordAction", ToolCall.class);
        m.setAccessible(true);
        m.invoke(null, new ToolCall("call-x", "tool_search", null));
        List<Map<String, Object>> actions = ToolCallHandler.drainActionTracking();
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).containsEntry("name", "tool_search");
        assertThat(actions.get(0).get("input")).isInstanceOf(Map.class);
    }
}
