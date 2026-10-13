package com.platform.api;

import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunEventRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.WorkflowValidator;
import com.platform.workflow.event.WorkflowRunSubscriberRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("WorkflowController 삭제와 비활성 워크플로우 처리 (CR-144, BIZ-116)")
class WorkflowControllerDeleteTest {

    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository runRepository;
    private WorkflowEngine engine;
    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        runRepository = mock(WorkflowRunRepository.class);
        engine = mock(WorkflowEngine.class);
        controller = new WorkflowController(
                workflowRepository,
                runRepository,
                engine,
                mock(WorkflowValidator.class),
                mock(WorkflowRunSubscriberRegistry.class),
                mock(WorkflowRunEventRepository.class),
                mock(com.platform.repository.ProjectRepository.class),
                mock(com.platform.auth.ResourceOwner.class));
    }

    private static WorkflowEntity workflow(String id) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(id);
        w.setName(id);
        w.setActive(true);
        return w;
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("TC-WF-018 삭제는 행을 지우지 않고 비활성화한다")
    void deleteDeactivates() {
        when(workflowRepository.findByIdAndIsActiveTrue("wf-1")).thenReturn(Optional.of(workflow("wf-1")));

        controller.delete("wf-1");

        ArgumentCaptor<WorkflowEntity> saved = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
        verify(workflowRepository, never()).deleteById(any());
        verify(workflowRepository, never()).delete(any());
    }

    @Test
    @DisplayName("TC-WF-019 삭제된 워크플로우의 상세, 수정, 재삭제는 404")
    void inactiveDefinitionIsNotFound() {
        when(workflowRepository.findByIdAndIsActiveTrue("gone")).thenReturn(Optional.empty());
        var request = new WorkflowController.WorkflowRequest(
                "gone", "gone", null, null, null, null, Map.of(), List.of(), null, null, null);

        assertNotFound(() -> controller.get("gone"));
        assertNotFound(() -> controller.update("gone", request));
        assertNotFound(() -> controller.delete("gone"));
        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-WF-020 삭제된 워크플로우는 실행과 재실행이 404 이고 run 을 만들지 않는다")
    void inactiveCannotRun() {
        when(workflowRepository.existsByIdAndIsActiveTrue("gone")).thenReturn(false);
        UUID sourceRunId = UUID.randomUUID();
        WorkflowRunEntity source = new WorkflowRunEntity();
        source.setId(sourceRunId);
        source.setWorkflowId("gone");
        when(runRepository.findById(sourceRunId)).thenReturn(Optional.of(source));

        assertNotFound(() -> controller.run("gone", Map.of()));
        assertNotFound(() -> controller.rerun(sourceRunId));
        verify(engine, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("TC-WF-021 기본 목록은 활성 워크플로우만 조회한다")
    void listShowsActiveOnly() {
        when(workflowRepository.findByIsActiveTrue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(workflow("wf-1"))));

        controller.list(0, 20, null, false);

        verify(workflowRepository).findByIsActiveTrue(any(Pageable.class));
        verify(workflowRepository, never()).findAll(any(Pageable.class));
    }
}
