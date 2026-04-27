package com.platform.tool.builtin;

import com.platform.orchestrator.stream.StreamEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-070 Phase A: ClaudeCodeTool의 STREAM_SINK + readStreamWithSink 동작 검증.
 *
 * stream-json NDJSON 라인을 InputStream으로 흘려보내고, sink가 적절한 StreamEvent를
 * 받는지 확인. 실제 CLI 프로세스는 띄우지 않음.
 */
class ClaudeCodeToolStreamingTest {

    private ClaudeCodeTool tool;
    private List<StreamEvent> received;

    @BeforeEach
    void setUp() {
        ClaudeCodeToolConfig config = new ClaudeCodeToolConfig();
        config.setEnabled(true);
        config.setExecutable("echo");
        config.setTimeoutSeconds(10);
        config.setMaxTurns(5);
        config.setDefaultAllowedTools("Read,Grep,Glob");
        config.setWorkingDirectory("");

        ClaudeCodeCircuitBreaker circuitBreaker = new ClaudeCodeCircuitBreaker();
        tool = new ClaudeCodeTool(config, circuitBreaker, null, null, null);

        received = new ArrayList<>();
        ClaudeCodeTool.setStreamSink(received::add);
    }

    @AfterEach
    void tearDown() {
        ClaudeCodeTool.clearStreamSink();
    }

    @Test
    void assistant_text_block_emits_TextDelta() throws Exception {
        String ndjson = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Hello world"}]}}
                """;

        String result = invokeReadStreamWithSink(ndjson);

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(StreamEvent.TextDelta.class);
        assertThat(((StreamEvent.TextDelta) received.get(0)).delta()).isEqualTo("Hello world");
        assertThat(result).contains("Hello world");
    }

    @Test
    void assistant_tool_use_block_emits_ToolUseStart() throws Exception {
        String ndjson = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01","name":"Read","input":{"file_path":"/tmp/foo"}}]}}
                """;

        invokeReadStreamWithSink(ndjson);

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(StreamEvent.ToolUseStart.class);
        StreamEvent.ToolUseStart e = (StreamEvent.ToolUseStart) received.get(0);
        assertThat(e.id()).isEqualTo("toolu_01");
        assertThat(e.name()).isEqualTo("Read");
        assertThat(e.input()).containsEntry("file_path", "/tmp/foo");
    }

    @Test
    void user_tool_result_block_emits_ToolResultEvent() throws Exception {
        String ndjson = """
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_01","content":"file contents","is_error":false}]}}
                """;

        invokeReadStreamWithSink(ndjson);

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(StreamEvent.ToolResultEvent.class);
        StreamEvent.ToolResultEvent e = (StreamEvent.ToolResultEvent) received.get(0);
        assertThat(e.toolUseId()).isEqualTo("toolu_01");
        assertThat(e.output()).isEqualTo("file contents");
        assertThat(e.isError()).isFalse();
    }

    @Test
    void result_event_does_not_emit() throws Exception {
        String ndjson = """
                {"type":"result","subtype":"success","result":"final answer","total_cost_usd":0.001}
                """;

        invokeReadStreamWithSink(ndjson);

        assertThat(received).isEmpty();
    }

    @Test
    void multiple_lines_emit_in_order() throws Exception {
        String ndjson = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"first"}]}}
                {"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"done"}]}}
                """;

        String result = invokeReadStreamWithSink(ndjson);

        assertThat(received).hasSize(4);
        assertThat(received.get(0)).isInstanceOf(StreamEvent.TextDelta.class);
        assertThat(received.get(1)).isInstanceOf(StreamEvent.ToolUseStart.class);
        assertThat(received.get(2)).isInstanceOf(StreamEvent.ToolResultEvent.class);
        assertThat(received.get(3)).isInstanceOf(StreamEvent.TextDelta.class);
        assertThat(result).contains("first").contains("done");
    }

    @Test
    void malformed_line_is_skipped_silently() throws Exception {
        String ndjson = """
                not-json garbage
                {"type":"assistant","message":{"content":[{"type":"text","text":"survived"}]}}
                """;

        invokeReadStreamWithSink(ndjson);

        assertThat(received).hasSize(1);
        assertThat(((StreamEvent.TextDelta) received.get(0)).delta()).isEqualTo("survived");
    }

    @Test
    void null_sink_does_not_throw() throws Exception {
        // sink 인자가 null이면 emit 자체가 silent — 예외 없이 누적만 수행
        Method m = ClaudeCodeTool.class.getDeclaredMethod(
                "readStreamWithSink", InputStream.class, Consumer.class);
        m.setAccessible(true);
        String ndjson = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}}\n";
        InputStream is = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        String result = (String) m.invoke(tool, is, (Consumer<StreamEvent>) null);
        assertThat(result).contains("\"text\":\"x\"");
    }

    private String invokeReadStreamWithSink(String ndjson) throws Exception {
        Method m = ClaudeCodeTool.class.getDeclaredMethod(
                "readStreamWithSink", InputStream.class, Consumer.class);
        m.setAccessible(true);
        InputStream is = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        Consumer<StreamEvent> sink = received::add;
        return (String) m.invoke(tool, is, sink);
    }
}
