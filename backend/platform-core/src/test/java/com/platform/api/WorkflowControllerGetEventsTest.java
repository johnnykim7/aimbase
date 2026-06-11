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

        assertThatThrownBy(() -> controller.getRunEvents(runId, false))
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

        ApiResponse<List<Map<String, Object>>> resp = controller.getRunEvents(runId, false);
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

        ApiResponse<List<Map<String, Object>>> resp = controller.getRunEvents(runId, false);
        assertThat(resp.data()).isEmpty();
    }

    @Test
    @DisplayName("CR-102: include_body=false 면 본문 미노출, true 면 본문 전문 노출")
    void includeBodyToggle() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(new WorkflowRunEntity()));

        WorkflowRunEventEntity llm = ev(runId, "s1", EventType.LLM_RESPONSE, null, 800L,
                Map.of("model", "claude-sonnet-4-5"), "trace-1");
        llm.setPromptText("[SYSTEM]\nyou are helpful\n\n[PROMPT]\n2+2?");
        llm.setResponseText("4");
        WorkflowRunEventEntity tool = ev(runId, "s2", EventType.TOOL_USE, "web_search", null,
                Map.of("input_keys", java.util.Set.of("query")), null);
        tool.setInputJson(Map.of("query", "aimbase"));
        WorkflowRunEventEntity end = ev(runId, "s1", EventType.STEP_END, null, 900L,
                Map.of("output_size", 1), null);
        end.setOutputText("4");

        when(eventRepository.findByRunIdOrderByCreatedAtAscIdAsc(runId))
                .thenReturn(List.of(llm, tool, end));

        // include_body=false → 본문 키 없음
        List<Map<String, Object>> off = controller.getRunEvents(runId, false).data();
        assertThat(off.get(0)).doesNotContainKeys("prompt_text", "response_text", "input_json", "output_text");

        // include_body=true → 본문 전문 노출 (절단 없음)
        List<Map<String, Object>> on = controller.getRunEvents(runId, true).data();
        assertThat(on.get(0)).containsEntry("prompt_text", "[SYSTEM]\nyou are helpful\n\n[PROMPT]\n2+2?");
        assertThat(on.get(0)).containsEntry("response_text", "4");
        assertThat(on.get(1)).containsEntry("input_json", Map.of("query", "aimbase"));
        assertThat(on.get(2)).containsEntry("output_text", "4");
    }

    // ─── CR-102: 신규 엔드포인트 ───

    @Test
    @DisplayName("CR-102: 이벤트 단건 조회 → 본문 전문 항상 포함")
    void getSingleEventWithBody() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEventEntity e = ev(runId, "s1", EventType.LLM_RESPONSE, null, 800L,
                Map.of("model", "claude-sonnet-4-5"), null);
        e.setPromptText("full prompt");
        e.setResponseText("full response");
        when(eventRepository.findById(7L)).thenReturn(Optional.of(e));

        Map<String, Object> body = controller.getRunEvent(runId, 7L).data();
        assertThat(body).containsEntry("prompt_text", "full prompt");
        assertThat(body).containsEntry("response_text", "full response");
    }

    @Test
    @DisplayName("CR-102: 이벤트가 다른 run 소속이면 404 (run 경계 검증)")
    void getSingleEventWrongRun() {
        WorkflowRunEventEntity e = ev(UUID.randomUUID(), "s1", EventType.STEP_END, null, 900L,
                Map.of("output_size", 1), null);
        when(eventRepository.findById(7L)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> controller.getRunEvent(UUID.randomUUID(), 7L))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("CR-102: 전체 run 횡단 목록 — workflow_id/status 필터가 searchRuns 로 위임")
    void allRunsDelegatesToSearch() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        when(runRepository.searchRuns(eq("wf-1"), eq("failed"), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(run)));

        ApiResponse<?> resp = controller.allRuns(0, 20, "wf-1", "failed");
        assertThat((List<?>) resp.data()).hasSize(1);
        verify(runRepository).searchRuns(eq("wf-1"), eq("failed"), any());
    }

    @Test
    @DisplayName("CR-102: run 단건 조회 (워크플로우 id 없이) — 미존재 시 404")
    void getRunByIdNotFound() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getRunById(runId))
                .isInstanceOf(ResponseStatusException.class)
                .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.NOT_FOUND);
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
