package com.platform.workflow.callback;

import com.platform.domain.ConnectionEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.ConnectionRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.tool.ToolResult;
import com.platform.tool.builtin.HttpRequestTool;
import com.platform.workflow.event.WorkflowEvents;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("실행 종료 회신 _callback (CR-174, PRD-363, BIZ-143)")
class RunCompletionCallbackTest {

    private WorkflowRunRepository runs;
    private ConnectionRepository connections;
    private HttpRequestTool http;
    private WorkflowRunEventRecorder recorder;
    private List<Long> sleeps;
    private RunCompletionCallback callback;

    @BeforeEach
    void setUp() {
        runs = mock(WorkflowRunRepository.class);
        connections = mock(ConnectionRepository.class);
        http = mock(HttpRequestTool.class);
        recorder = mock(WorkflowRunEventRecorder.class);
        sleeps = new ArrayList<>();
        callback = new RunCompletionCallback(runs, connections, http, recorder, 2, 5000L,
                Runnable::run, sleeps::add);
    }

    private static Map<String, Object> binding(String connectionId, String path, String resultStep) {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        if (connectionId != null) b.put("connection_id", connectionId);
        if (path != null) b.put("path", path);
        if (resultStep != null) b.put("result_step", resultStep);
        return Map.of(RunCompletionCallback.INPUT_KEY, b);
    }

    private void httpConnection(String id) {
        ConnectionEntity c = new ConnectionEntity();
        c.setId(id);
        c.setType("HTTP");
        when(connections.findById(id)).thenReturn(Optional.of(c));
    }

    private WorkflowRunEntity run(Map<String, Object> input, Map<String, Object> stepResults) {
        WorkflowRunEntity r = new WorkflowRunEntity();
        r.setId(UUID.randomUUID());
        r.setWorkflowId("poomgo-change-repair");
        r.setSessionId("sess-1");
        r.setStatus("completed");
        r.setInputData(input);
        r.setStepResults(stepResults);
        r.setCompletedAt(OffsetDateTime.of(2026, 11, 9, 6, 2, 11, 0, ZoneOffset.UTC));
        when(runs.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("TC-WF-034 칸이 틀리면 callback_invalid:칸 으로 막고, 없으면 그냥 지나간다")
    void validateRejectsBadFields() {
        httpConnection("bp-3plgw");

        assertThatThrownBy(() -> callback.validate(binding(null, "/r", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("callback_invalid:connection_id");
        assertThatThrownBy(() -> callback.validate(binding("bp-3plgw", "no-slash", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("callback_invalid:path");
        assertThatThrownBy(() -> callback.validate(Map.of(RunCompletionCallback.INPUT_KEY, "text")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("callback_invalid:_callback");
        assertThatThrownBy(() -> callback.validate(binding("unknown", "/r", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("callback_invalid:connection_id");

        callback.validate(binding("bp-3plgw", "/api/v1/poomgo/repairs/x/result", "repair"));
        callback.validate(Map.of("change_id", "x"));
        callback.validate(null);
    }

    @Test
    @DisplayName("TC-WF-034 HTTP 가 아닌 연결은 connection_id 로 막는다")
    void validateRejectsNonHttpConnection() {
        ConnectionEntity c = new ConnectionEntity();
        c.setId("anthropic-1");
        c.setType("LLM");
        when(connections.findById("anthropic-1")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> callback.validate(binding("anthropic-1", "/r", null)))
                .hasMessage("callback_invalid:connection_id");
    }

    @Test
    @DisplayName("TC-WF-035 보강: 자식 run 과 _callback 없는 run 은 회신하지 않는다")
    void skipsChildAndUnboundRuns() {
        WorkflowRunEntity plain = run(Map.of("change_id", "x"), Map.of());

        callback.onRunCompleted(new WorkflowEvents.RunCompleted(plain.getId(), null, "completed", 10L));
        callback.onRunCompleted(new WorkflowEvents.RunCompleted(UUID.randomUUID(), UUID.randomUUID(), "completed", 10L));

        verify(http, never()).execute(any(), any());
    }

    @Test
    @DisplayName("TC-WF-035, TC-WF-036 회신 본문은 run 상태, error, result_step 의 structured_data(없으면 output)를 싣는다")
    @SuppressWarnings("unchecked")
    void payloadCarriesStructuredDataOrOutput() {
        Map<String, Object> structured = Map.of("result_kind", "CODE", "pr", Map.of("number", 57));
        WorkflowRunEntity r = run(binding("bp-3plgw", "/result", "repair"),
                Map.of("repair", Map.of("output", "{\"result_kind\":\"CODE\"}", "structured_data", structured)));
        r.setStatus("failed");
        r.setError(Map.of("reason", "approval_timeout", "detail", "2026-11-09T10:12:00Z"));
        when(http.execute(any(), any())).thenReturn(ToolResult.ok(Map.of("status", 200), "200"));

        callback.onRunCompleted(new WorkflowEvents.RunCompleted(r.getId(), null, "failed", 14403000L));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(http).execute(captor.capture(), any());
        Map<String, Object> req = captor.getValue();
        assertThat(req).containsEntry("connection_id", "bp-3plgw").containsEntry("method", "POST")
                .containsEntry("path", "/result");
        Map<String, Object> body = (Map<String, Object>) req.get("body");
        assertThat(body).containsEntry("run_id", r.getId().toString())
                .containsEntry("workflow_id", "poomgo-change-repair")
                .containsEntry("status", "failed")
                .containsEntry("result", structured)
                .containsEntry("completed_at", "2026-11-09T06:02:11Z")
                .containsEntry("duration_ms", 14403000L);
        assertThat((Map<String, Object>) body.get("error")).containsEntry("reason", "approval_timeout");

        WorkflowRunEntity textOnly = run(binding("bp-3plgw", "/result", "repair"),
                Map.of("repair", Map.of("output", "plain text")));
        callback.onRunCompleted(new WorkflowEvents.RunCompleted(textOnly.getId(), null, "completed", 1L));
        verify(http, times(2)).execute(captor.capture(), any());
        Map<String, Object> body2 = (Map<String, Object>) captor.getValue().get("body");
        assertThat(body2).containsEntry("result", "plain text").containsEntry("error", null);
    }

    @Test
    @DisplayName("TC-WF-037 2xx 가 아니면 설정 횟수까지 다시 보내고, 끝내 실패하면 CALLBACK_FAILED 만 남긴다")
    void retriesThenRecordsFailure() {
        WorkflowRunEntity r = run(binding("bp-3plgw", "/result", null), Map.of());
        when(http.execute(any(), any()))
                .thenReturn(ToolResult.error("HTTP 503"))
                .thenReturn(ToolResult.ok(Map.of("status", 200), "200"));

        callback.onRunCompleted(new WorkflowEvents.RunCompleted(r.getId(), null, "completed", 1L));

        verify(http, times(2)).execute(any(), any());
        assertThat(sleeps).containsExactly(5000L);
        verify(recorder, never()).callbackFailed(any(), anyString(), anyString(), anyInt(), any());

        WorkflowRunEntity r2 = run(binding("bp-3plgw", "/result", null), Map.of());
        when(http.execute(any(), any())).thenThrow(new IllegalStateException("connection refused"));

        callback.onRunCompleted(new WorkflowEvents.RunCompleted(r2.getId(), null, "cancelled", 1L));

        verify(http, times(5)).execute(any(), any());
        verify(recorder).callbackFailed(eq(r2.getId()), eq("bp-3plgw"), eq("/result"), eq(3), eq("connection refused"));
    }
}
