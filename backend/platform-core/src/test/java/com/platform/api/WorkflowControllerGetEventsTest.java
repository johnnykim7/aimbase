package com.platform.api;

import com.platform.domain.WorkflowRunEntity;
import com.platform.domain.WorkflowRunEventEntity;
import com.platform.domain.WorkflowRunEventEntity.EventType;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunEventRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.WorkflowValidator;
import com.platform.workflow.event.WorkflowRunSubscriberRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * CR-090: GET /api/v1/workflows/runs/{runId}/events 컨트롤러 단위 테스트.
 */
@DisplayName("WorkflowController.getRunEvents — CR-090")
class WorkflowControllerGetEventsTest {

    private WorkflowController controller;
    private WorkflowRunRepository runRepository;
    private WorkflowRunEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        eventRepository = mock(WorkflowRunEventRepository.class);
        controller = new WorkflowController(
                mock(WorkflowRepository.class),
                runRepository,
                mock(WorkflowEngine.class),
                mock(WorkflowValidator.class),
                mock(WorkflowRunSubscriberRegistry.class),
                eventRepository);
    }

    @Test
    @DisplayName("존재하지 않는 runId → 404 ResponseStatusException")
    void notFound() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getRunEvents(runId))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("이벤트 시간순 반환 + payload/메타 매핑")
    void returnsEventsOrdered() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(new WorkflowRunEntity()));

        WorkflowRunEventEntity e1 = ev(runId, "s1", EventType.STEP_START, null, null,
                Map.of("step_type", "LLM_CALL"), null);
        WorkflowRunEventEntity e2 = ev(runId, "s1", EventType.LLM_RESPONSE, null, 800L,
                Map.of("model", "claude-sonnet-4-5", "in_tok", 100, "out_tok", 50), "trace-1");
        WorkflowRunEventEntity e3 = ev(runId, "s1", EventType.STEP_END, null, 900L,
                Map.of("output_size", 200), null);

        when(eventRepository.findByRunIdOrderByCreatedAtAscIdAsc(runId))
                .thenReturn(List.of(e1, e2, e3));

        ApiResponse<List<Map<String, Object>>> resp = controller.getRunEvents(runId);
        List<Map<String, Object>> body = resp.data();

        assertThat(body).hasSize(3);
        assertThat(body.get(0)).containsEntry("event_type", "STEP_START");
        assertThat(body.get(1)).containsEntry("event_type", "LLM_RESPONSE");
        assertThat(body.get(1)).containsEntry("duration_ms", 800L);
        assertThat(body.get(1)).containsEntry("trace_id", "trace-1");
        assertThat(body.get(2)).containsEntry("event_type", "STEP_END");
        assertThat(body.get(2)).containsEntry("duration_ms", 900L);
    }

    @Test
    @DisplayName("빈 결과 → 빈 배열 반환 (404 아님)")
    void emptyEvents() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(new WorkflowRunEntity()));
        when(eventRepository.findByRunIdOrderByCreatedAtAscIdAsc(runId)).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> resp = controller.getRunEvents(runId);
        assertThat(resp.data()).isEmpty();
    }

    private WorkflowRunEventEntity ev(UUID runId, String stepId, EventType type,
                                       String toolName, Long durationMs,
                                       Map<String, Object> payload, String traceId) {
        WorkflowRunEventEntity e = new WorkflowRunEventEntity();
        e.setRunId(runId);
        e.setStepId(stepId);
        e.setEventType(type);
        e.setToolName(toolName);
        e.setDurationMs(durationMs);
        e.setPayload(payload);
        e.setTraceId(traceId);
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
