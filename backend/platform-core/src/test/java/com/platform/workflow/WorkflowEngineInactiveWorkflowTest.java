package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.event.WorkflowEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowEngine 삭제된 워크플로우 실행 거부 (CR-144, BIZ-116)")
class WorkflowEngineInactiveWorkflowTest {

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Test
    @DisplayName("TC-WF-020 크론, 원격 트리거가 부르는 execute 도 삭제된 워크플로우는 run 을 만들지 않는다")
    void executeRejectsInactive() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        when(workflowRepository.findByIdAndIsActiveTrue("gone")).thenReturn(Optional.empty());
        WorkflowEngine engine = new WorkflowEngine(
                workflowRepository,
                runRepository,
                mock(PendingApprovalRepository.class),
                new ObjectMapper(),
                List.of(),
                mock(PlatformMetrics.class),
                mock(WorkflowEventPublisher.class),
                null,
                null,
                null,
                provider(mock(com.platform.agent.ActiveCliWorkerRegistry.class)),
                provider(mock(com.platform.llm.ConnectionAdapterFactory.class)),
                provider(new WorkflowCancellationRegistry()),
                null,  // CR-147: stepSkillsProvider
                null); // CR-164: devWorkRunBindingProvider

        assertThatThrownBy(() -> engine.execute("gone", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workflow not found");
        verify(workflowRepository, never()).findById(any());
        verify(runRepository, never()).save(any());
    }
}
