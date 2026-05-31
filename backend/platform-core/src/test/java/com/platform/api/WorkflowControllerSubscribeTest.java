package com.platform.api;

import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.WorkflowValidator;
import com.platform.workflow.event.WorkflowRunSubscriberRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-058: WorkflowController#subscribeRun SSE 엔드포인트 동작 검증.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowControllerSubscribeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowEngine workflowEngine;
    @Mock private WorkflowValidator workflowValidator;
    @Mock private WorkflowRunSubscriberRegistry subscriberRegistry;

    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowController(workflowRepository, workflowRunRepository,
                workflowEngine, workflowValidator, subscriberRegistry,
                org.mockito.Mockito.mock(com.platform.repository.WorkflowRunEventRepository.class));
    }

    @Test
    void subscribeRun_returns404WhenRunMissing() {
        UUID runId = UUID.randomUUID();
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.subscribeRun(runId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Workflow run not found");
    }

    @Test
    void subscribeRun_registersEmitterForActiveRun() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(runId);
        run.setWorkflowId("wf-1");
        run.setSessionId("sess-1");
        run.setStatus("running");
        run.setCurrentStep("step_a");
        run.setStartedAt(OffsetDateTime.now());
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        SseEmitter emitter = controller.subscribeRun(runId);

        assertThat(emitter).isNotNull();
        verify(subscriberRegistry).register(runId, emitter);
    }

    @Test
    void subscribeRun_skipsRegistrationForCompletedRun() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(runId);
        run.setWorkflowId("wf-1");
        run.setStatus("completed");
        OffsetDateTime start = OffsetDateTime.now().minusSeconds(5);
        run.setStartedAt(start);
        run.setCompletedAt(OffsetDateTime.now());
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        SseEmitter emitter = controller.subscribeRun(runId);

        assertThat(emitter).isNotNull();
        // 종료된 런은 즉시 close 되므로 register 호출되지 않아야 함
        verify(subscriberRegistry, never()).register(runId, emitter);
    }

    @Test
    void subscribeRun_skipsRegistrationForFailedRun() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(runId);
        run.setWorkflowId("wf-1");
        run.setStatus("failed");
        run.setStartedAt(OffsetDateTime.now());
        run.setCompletedAt(OffsetDateTime.now());
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        SseEmitter emitter = controller.subscribeRun(runId);

        verify(subscriberRegistry, never()).register(runId, emitter);
    }
}
