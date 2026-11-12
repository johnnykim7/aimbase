package com.platform.orchestrator.stream;

import com.platform.llm.model.LLMStreamChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEventTest {

    @Test
    @DisplayName("CLI 도구 사용 관찰은 위젯 도구 시작 이벤트가 된다")
    void observedToolUseBecomesToolUseStart() {
        var chunk = LLMStreamChunk.observedToolUse("run-1", "claude-sonnet-4-5",
                new LLMStreamChunk.ObservedTool("toolu_1", "Bash", Map.of("command", "ls"), null, null));

        StreamEvent ev = StreamEvent.ofObservedTool(chunk);

        assertThat(ev).isEqualTo(new StreamEvent.ToolUseStart("toolu_1", "Bash", Map.of("command", "ls")));
    }

    @Test
    @DisplayName("CLI 도구 결과 관찰은 같은 id 의 위젯 도구 결과 이벤트가 된다")
    void observedToolResultBecomesToolResultEvent() {
        var chunk = LLMStreamChunk.observedToolResult("run-1", "claude-sonnet-4-5",
                new LLMStreamChunk.ObservedTool("toolu_1", "Bash", null, "a.txt", 12L));

        StreamEvent ev = StreamEvent.ofObservedTool(chunk);

        assertThat(ev).isEqualTo(new StreamEvent.ToolResultEvent("toolu_1", "a.txt", false));
    }

    @Test
    @DisplayName("id 와 이름이 비어 온 관찰도 SSE 로 실을 수 있게 빈 글자로 바꾼다")
    void missingIdAndNameBecomeEmpty() {
        var chunk = LLMStreamChunk.observedToolUse("run-1", "m",
                new LLMStreamChunk.ObservedTool(null, null, null, null, null));

        StreamEvent ev = StreamEvent.ofObservedTool(chunk);

        assertThat(ev).isEqualTo(new StreamEvent.ToolUseStart("", "", null));
    }

    @Test
    @DisplayName("관찰이 아닌 답 조각은 도구 이벤트로 바꾸지 않는다")
    void textChunkIsNotATool() {
        assertThat(StreamEvent.ofObservedTool(LLMStreamChunk.text("run-1", "m", "안녕"))).isNull();
    }
}
