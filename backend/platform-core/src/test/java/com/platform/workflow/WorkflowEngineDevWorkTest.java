package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.devwork.DevWorkChangeSource;
import com.platform.devwork.DevWorkItemRunBinding;
import com.platform.devwork.DevWorkItemService;
import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.event.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowEngine 개발 작업 잇기와 종료 신호 빈자리 (CR-164, BIZ-130)")
class WorkflowEngineDevWorkTest {

    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository runRepository;
    private PendingApprovalRepository approvalRepository;
    private WorkflowEventPublisher publisher;
    private DevWorkItemService devWorkService;
    private WorkflowEngine engine;

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        runRepository = mock(WorkflowRunRepository.class);
        approvalRepository = mock(PendingApprovalRepository.class);
        publisher = mock(WorkflowEventPublisher.class);
        devWorkService = mock(DevWorkItemService.class);
        when(runRepository.save(any())).thenAnswer(inv -> {
            WorkflowRunEntity r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        engine = new WorkflowEngine(
                workflowRepository,
                runRepository,
                approvalRepository,
                new ObjectMapper(),
                List.of(),
                mock(PlatformMetrics.class),
                publisher,
                null,
                null,
                null,
                provider(mock(com.platform.agent.ActiveCliWorkerRegistry.class)),
                provider(mock(com.platform.llm.ConnectionAdapterFactory.class)),
                provider(new WorkflowCancellationRegistry()),
                null,
                provider(new DevWorkItemRunBinding(devWorkService, runRepository)));
    }

    private WorkflowEntity emptyWorkflow(String id) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(id);
        w.setSteps(List.of());
        when(workflowRepository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    @DisplayName("TC-DW-108 _dev_work_item 칸이 틀리면 execute 가 막고 run 을 저장하지 않는다")
    void invalidBindingCreatesNoRun() {
        emptyWorkflow("wf");

        assertThatThrownBy(() -> engine.execute("wf",
                Map.of(DevWorkItemRunBinding.INPUT_KEY, Map.of("feature_name", "f", "stage", "s")), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dev_work_item_invalid:work_item_id");
        verify(runRepository, never()).save(any());
        verify(devWorkService, never()).report(any(), any(), any());
    }

    @Test
    @DisplayName("TC-DW-109 맞는 칸이면 run 을 저장한 뒤 IN_PROGRESS 를 남긴다")
    void validBindingReportsStart() {
        emptyWorkflow("wf");

        WorkflowRunEntity run = engine.execute("wf", Map.of(DevWorkItemRunBinding.INPUT_KEY,
                Map.of("work_item_id", "W-1", "feature_name", "f", "stage", "실증")), null);

        verify(devWorkService).report(any(), eq(DevWorkChangeSource.WORKFLOW_RUN), eq(run.getId()));
    }

    @Test
    @DisplayName("TC-DW-112 단계 없는 워크플로우 실행도 종료 신호 completed 를 낸다")
    void emptyStepsPublishesCompleted() {
        emptyWorkflow("wf");

        WorkflowRunEntity run = engine.execute("wf", Map.of(), null);

        verify(publisher, timeout(3000)).runCompleted(eq(run.getId()), isNull(), eq("completed"), anyLong());
    }

    @Test
    @DisplayName("TC-DW-112 승인 거절도 종료 신호 failed 를 낸다")
    void approvalRejectionPublishesFailed() {
        WorkflowRunEntity paused = new WorkflowRunEntity();
        paused.setId(UUID.randomUUID());
        paused.setStatus("pending_approval");
        when(runRepository.findById(paused.getId())).thenReturn(Optional.of(paused));
        when(approvalRepository.findByActionLogId(paused.getId())).thenReturn(List.of());

        engine.resume(paused.getId(), false, "no");

        verify(publisher).runCompleted(eq(paused.getId()), isNull(), eq("failed"), anyLong());
    }
}
