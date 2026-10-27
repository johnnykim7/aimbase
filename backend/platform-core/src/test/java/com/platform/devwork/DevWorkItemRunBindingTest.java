package com.platform.devwork;

import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.event.WorkflowEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("워크플로우 실행과 개발 작업 잇기 (CR-164, BIZ-130)")
class DevWorkItemRunBindingTest {

    private DevWorkItemService service;
    private WorkflowRunRepository runs;
    private DevWorkItemRunBinding binding;

    @BeforeEach
    void setUp() {
        service = mock(DevWorkItemService.class);
        runs = mock(WorkflowRunRepository.class);
        binding = new DevWorkItemRunBinding(service, runs);
    }

    private static WorkflowRunEntity run(Map<String, Object> input, UUID parentRunId) {
        WorkflowRunEntity r = new WorkflowRunEntity();
        r.setId(UUID.randomUUID());
        r.setInputData(input);
        r.setParentRunId(parentRunId);
        return r;
    }

    private static Map<String, Object> boundInput() {
        return Map.of(DevWorkItemRunBinding.INPUT_KEY, Map.of(
                "work_item_id", "W-1", "feature_name", "주문 취소 화면", "stage", "실증",
                "evidence_url", "https://e.com/run"));
    }

    @Test
    @DisplayName("TC-DW-107 _dev_work_item 이 없는 실행은 작업 상태를 건드리지 않는다")
    void noBindingNoReport() {
        binding.validate(Map.of("x", 1));
        binding.onRunStarted(run(Map.of("x", 1), null));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("TC-DW-108 칸이 틀리면 validate 가 그 칸 이름으로 막는다")
    void invalidBindingRejected() {
        assertThatThrownBy(() -> binding.validate(Map.of(DevWorkItemRunBinding.INPUT_KEY,
                Map.of("feature_name", "f", "stage", "s"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dev_work_item_invalid:work_item_id");
        assertThatThrownBy(() -> binding.validate(Map.of(DevWorkItemRunBinding.INPUT_KEY, "W-1")))
                .hasMessage("dev_work_item_invalid:_dev_work_item");
    }

    @Test
    @DisplayName("TC-DW-109 실행이 시작되면 IN_PROGRESS 를 출처 WORKFLOW_RUN 과 run id 로 남긴다")
    void startReportsInProgress() {
        WorkflowRunEntity r = run(boundInput(), null);
        binding.onRunStarted(r);

        ArgumentCaptor<DevWorkItemService.Report> report = ArgumentCaptor.forClass(DevWorkItemService.Report.class);
        verify(service).report(report.capture(), eq(DevWorkChangeSource.WORKFLOW_RUN), eq(r.getId()));
        assertThat(report.getValue().status()).isEqualTo(DevWorkStatus.IN_PROGRESS);
        assertThat(report.getValue().workItemId()).isEqualTo("W-1");
        assertThat(report.getValue().evidenceUrl()).isEqualTo("https://e.com/run");
    }

    @Test
    @DisplayName("TC-DW-110 실행 끝 completed, failed, cancelled 를 각각 COMPLETED, FAILED, CANCELLED 로 남긴다")
    void endMapsStatus() {
        Map<String, DevWorkStatus> cases = Map.of(
                "completed", DevWorkStatus.COMPLETED,
                "failed", DevWorkStatus.FAILED,
                "cancelled", DevWorkStatus.CANCELLED);
        cases.forEach((runStatus, expected) -> {
            WorkflowRunEntity r = run(boundInput(), null);
            when(runs.findById(r.getId())).thenReturn(Optional.of(r));
            binding.onRunCompleted(new WorkflowEvents.RunCompleted(r.getId(), null, runStatus, 10L));

            ArgumentCaptor<DevWorkItemService.Report> report = ArgumentCaptor.forClass(DevWorkItemService.Report.class);
            verify(service).report(report.capture(), eq(DevWorkChangeSource.WORKFLOW_RUN), eq(r.getId()));
            assertThat(report.getValue().status()).isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("TC-DW-111 부모가 있는 자식 run 은 잇지 않는다")
    void childRunIgnored() {
        WorkflowRunEntity child = run(boundInput(), UUID.randomUUID());
        binding.onRunStarted(child);
        binding.onRunCompleted(new WorkflowEvents.RunCompleted(child.getId(), child.getParentRunId(), "completed", 1L));
        verifyNoInteractions(service);
        verify(runs, never()).findById(any());
    }

    @Test
    @DisplayName("상태 기록이 실패해도 실행 종료 신호를 받는 다른 쪽으로 예외를 넘기지 않는다")
    void reportFailureIsContained() {
        WorkflowRunEntity r = run(boundInput(), null);
        when(runs.findById(r.getId())).thenReturn(Optional.of(r));
        when(service.report(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        binding.onRunCompleted(new WorkflowEvents.RunCompleted(r.getId(), null, "failed", 1L));
        binding.onRunStarted(r);
    }
}
