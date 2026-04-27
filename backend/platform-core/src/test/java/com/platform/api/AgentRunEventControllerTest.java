package com.platform.api;

import com.platform.agent.AgentRunEventRouter;
import com.platform.orchestrator.stream.StreamEvent;
import com.platform.tenant.TenantContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-070 Phase B: AgentRunEventController 단위 테스트.
 *
 * NDJSON 라인을 InputStream 으로 흘려보내고, 라우터에 등록된 sink 가 받는지 검증.
 */
class AgentRunEventControllerTest {

    private AgentRunEventRouter router;
    private AgentRunEventController controller;

    @BeforeEach
    void setUp() {
        router = new AgentRunEventRouter();
        controller = new AgentRunEventController(router);
        TenantContext.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ndjson_stream_dispatches_each_line() throws Exception {
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register("tenant-a", received::add);

        String body = """
                {"type":"text_delta","delta":"hello"}
                {"type":"tool_use_start","id":"t1","name":"Read","input":{"file_path":"/tmp/x"}}
                {"type":"tool_result","tool_use_id":"t1","output":"contents","is_error":false}
                """;

        Map<String, Object> result = controller.push(UUID.randomUUID(), runId, requestWithBody(body));

        assertThat(result).containsEntry("dispatched", 3);
        assertThat(result).containsEntry("dropped", 0);
        assertThat(received).hasSize(3);
        assertThat(received.get(0)).isInstanceOf(StreamEvent.TextDelta.class);
        assertThat(received.get(1)).isInstanceOf(StreamEvent.ToolUseStart.class);
        assertThat(received.get(2)).isInstanceOf(StreamEvent.ToolResultEvent.class);
    }

    @Test
    void unknown_runId_increments_dropped() throws Exception {
        String body = "{\"type\":\"text_delta\",\"delta\":\"x\"}\n";
        Map<String, Object> result = controller.push(
                UUID.randomUUID(), "run-nonexistent", requestWithBody(body));

        assertThat(result).containsEntry("dispatched", 0);
        assertThat(result).containsEntry("dropped", 1);
    }

    @Test
    void malformed_line_counts_as_dropped() throws Exception {
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register("tenant-a", received::add);

        String body = """
                not-json-garbage
                {"type":"text_delta","delta":"survived"}
                {"type":"unknown_kind"}
                """;

        Map<String, Object> result = controller.push(UUID.randomUUID(), runId, requestWithBody(body));

        assertThat(result).containsEntry("dispatched", 1);
        assertThat(result).containsEntry("dropped", 2);
        assertThat(received).hasSize(1);
        assertThat(((StreamEvent.TextDelta) received.get(0)).delta()).isEqualTo("survived");
    }

    @Test
    void tenant_mismatch_drops_event() throws Exception {
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register("tenant-b", received::add);

        String body = "{\"type\":\"text_delta\",\"delta\":\"x\"}\n";
        Map<String, Object> result = controller.push(UUID.randomUUID(), runId, requestWithBody(body));

        assertThat(result).containsEntry("dispatched", 0);
        assertThat(result).containsEntry("dropped", 1);
        assertThat(received).isEmpty();
    }

    @Test
    void single_event_pushOne_works() {
        List<StreamEvent> received = new ArrayList<>();
        String runId = router.register("tenant-a", received::add);

        Map<String, Object> result = controller.pushOne(
                UUID.randomUUID(), runId,
                Map.of("type", "text_delta", "delta", "hi"));

        assertThat(result).containsEntry("dispatched", 1);
        assertThat(received).hasSize(1);
    }

    @Test
    void pushOne_invalid_event_returns_dropped() {
        Map<String, Object> result = controller.pushOne(
                UUID.randomUUID(), "run-x",
                Map.of("type", "unknown_kind"));

        assertThat(result).containsEntry("dispatched", 0);
        assertThat(result).containsEntry("dropped", 1);
    }

    private HttpServletRequest requestWithBody(String body) throws IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        ServletInputStream sis = new ServletInputStream() {
            private final InputStream delegate = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));

            @Override
            public boolean isFinished() { return false; }

            @Override
            public boolean isReady() { return true; }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {}

            @Override
            public int read() throws IOException { return delegate.read(); }
        };
        when(req.getInputStream()).thenReturn(sis);
        return req;
    }
}
