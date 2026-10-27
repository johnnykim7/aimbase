package com.platform.workflow;

import com.platform.devwork.DevWorkChangeSource;
import com.platform.devwork.DevWorkItemStatusChanged;
import com.platform.devwork.DevWorkStatus;
import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("상태 변경 이벤트 트리거 워크플로우 실행 (CR-164, BIZ-131)")
class WorkflowEventTriggerDispatcherTest {

    private WorkflowRepository workflowRepository;
    private WorkflowEngine engine;
    private WorkflowEventTriggerDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        engine = mock(WorkflowEngine.class);
        dispatcher = new WorkflowEventTriggerDispatcher(workflowRepository, engine);
    }

    private static WorkflowEntity workflow(String id) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(id);
        return w;
    }

    private static WorkflowRunEntity run() {
        WorkflowRunEntity r = new WorkflowRunEntity();
        r.setId(UUID.randomUUID());
        return r;
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("TC-DW-113 트리거를 단 활성 워크플로우마다 실행하고 입력에 변경 값과 UTC reported_at 을 싣는다")
    void runsEveryTriggeredWorkflow() {
        when(workflowRepository.findActiveByTrigger("event", "DEV_WORK_ITEM_STATUS_CHANGED"))
                .thenReturn(List.of(workflow("notify-a"), workflow("notify-b")));
        when(engine.execute(any(), any(), isNull())).thenReturn(run());
        UUID changeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        dispatcher.onDevWorkItemStatusChanged(new DevWorkItemStatusChanged("W-1", "주문 취소 화면", "실증",
                DevWorkStatus.FAILED, "https://e.com/run", OffsetDateTime.parse("2026-10-28T15:30:00.120+09:00"),
                changeId, DevWorkChangeSource.WORKFLOW_RUN, runId));

        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(engine).execute(eq("notify-a"), input.capture(), isNull());
        verify(engine).execute(eq("notify-b"), any(), isNull());
        assertThat(input.getValue())
                .containsEntry("work_item_id", "W-1")
                .containsEntry("feature_name", "주문 취소 화면")
                .containsEntry("stage", "실증")
                .containsEntry("status", "FAILED")
                .containsEntry("evidence_url", "https://e.com/run")
                .containsEntry("reported_at", "2026-10-28T06:30:00.12Z")
                .containsEntry("change_id", changeId.toString())
                .containsEntry("source", "WORKFLOW_RUN")
                .containsEntry("source_run_id", runId.toString());
    }

    @Test
    @DisplayName("TC-DW-114 한 워크플로우 실행이 실패해도 다음 워크플로우는 실행하고 예외를 밖으로 내지 않는다")
    void oneFailureDoesNotStopOthers() {
        when(workflowRepository.findActiveByTrigger("event", "DEV_WORK_ITEM_STATUS_CHANGED"))
                .thenReturn(List.of(workflow("broken"), workflow("ok")));
        when(engine.execute(eq("broken"), any(), isNull())).thenThrow(new IllegalArgumentException("Workflow not found: broken"));
        when(engine.execute(eq("ok"), any(), isNull())).thenReturn(run());

        dispatcher.onDevWorkItemStatusChanged(event(null, null));

        verify(engine).execute(eq("ok"), any(), isNull());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("TC-DW-115 증빙 주소와 출처 run 이 없으면 입력에 빈 글로 싣는다")
    void missingValuesAreEmptyText() {
        when(workflowRepository.findActiveByTrigger("event", "DEV_WORK_ITEM_STATUS_CHANGED"))
                .thenReturn(List.of(workflow("notify-a")));
        when(engine.execute(any(), any(), isNull())).thenReturn(run());

        dispatcher.onDevWorkItemStatusChanged(event(null, null));

        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(engine).execute(eq("notify-a"), input.capture(), isNull());
        assertThat(input.getValue()).containsEntry("evidence_url", "").containsEntry("source_run_id", "");
    }

    private static DevWorkItemStatusChanged event(String evidenceUrl, UUID runId) {
        return new DevWorkItemStatusChanged("W-1", "f", "기능", DevWorkStatus.IN_PROGRESS, evidenceUrl,
                OffsetDateTime.parse("2026-10-28T06:30:00Z"), UUID.randomUUID(), DevWorkChangeSource.API, runId);
    }
}
