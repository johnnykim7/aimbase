package com.platform.workflow.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * CR-058: WorkflowEventPublisher 가 이벤트 객체를 올바르게 구성하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowEventPublisherTest {

    @Mock private ApplicationEventPublisher springPublisher;

    private WorkflowEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WorkflowEventPublisher(springPublisher);
    }

    @Test
    void stepRunning_publishesRunningEvent() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        publisher.stepRunning(runId, null, "fetch_order", now);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.StepStatusChanged ev = (WorkflowEvents.StepStatusChanged) captor.getValue();
        assertThat(ev.status()).isEqualTo("running");
        assertThat(ev.runId()).isEqualTo(runId);
        assertThat(ev.stepId()).isEqualTo("fetch_order");
        assertThat(ev.startedAt()).isEqualTo(now);
    }

    @Test
    void stepCompleted_computesDuration() {
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now();
        Instant end = start.plusMillis(150);

        publisher.stepCompleted(runId, null, "s1", start, end, null, Map.of("k", "v"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.StepStatusChanged ev = (WorkflowEvents.StepStatusChanged) captor.getValue();
        assertThat(ev.status()).isEqualTo("completed");
        assertThat(ev.durationMs()).isEqualTo(150L);
        assertThat(ev.outputPreview()).containsEntry("k", "v");
    }

    @Test
    void stepFailed_includesErrorMessage() {
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now();
        Instant end = start.plusMillis(50);

        publisher.stepFailed(runId, null, "s1", start, end, "boom");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.StepStatusChanged ev = (WorkflowEvents.StepStatusChanged) captor.getValue();
        assertThat(ev.status()).isEqualTo("failed");
        assertThat(ev.errorMessage()).isEqualTo("boom");
    }

    @Test
    void approvalRequired_defaultsEmptyApproversWhenNull() {
        UUID runId = UUID.randomUUID();

        publisher.approvalRequired(runId, null, "confirm", "refund_policy",
                "10만원 초과", null, Instant.now());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.ApprovalRequired ev = (WorkflowEvents.ApprovalRequired) captor.getValue();
        assertThat(ev.approvers()).isEqualTo(List.of());
        assertThat(ev.policyId()).isEqualTo("refund_policy");
    }

    // ─── CR-084 P4: iterationIndex 하위호환 + cyclic 회차 ──────────────

    @Test
    void stepRunning_legacyOverload_iterationIndexNull() {
        // 기존 4-arg 호출은 iterationIndex=null (DAG 동작 불변)
        publisher.stepRunning(UUID.randomUUID(), null, "s1", Instant.now());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.StepStatusChanged ev = (WorkflowEvents.StepStatusChanged) captor.getValue();
        assertThat(ev.iterationIndex()).isNull();
    }

    @Test
    void stepRunning_cyclicOverload_carriesIterationIndex() {
        publisher.stepRunning(UUID.randomUUID(), null, "loop_node", Instant.now(), 3);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.StepStatusChanged ev = (WorkflowEvents.StepStatusChanged) captor.getValue();
        assertThat(ev.iterationIndex()).isEqualTo(3);
        assertThat(ev.status()).isEqualTo("running");
    }

    @Test
    void stepCompleted_cyclicOverload_carriesIterationIndex() {
        Instant start = Instant.now();
        publisher.stepCompleted(UUID.randomUUID(), null, "loop_node",
                start, start.plusMillis(10), null, Map.of("o", 1), 2);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.StepStatusChanged ev = (WorkflowEvents.StepStatusChanged) captor.getValue();
        assertThat(ev.iterationIndex()).isEqualTo(2);
        assertThat(ev.status()).isEqualTo("completed");
    }

    @Test
    void stepCompleted_legacyOverload_iterationIndexNull() {
        Instant start = Instant.now();
        publisher.stepCompleted(UUID.randomUUID(), null, "s1",
                start, start.plusMillis(5), null, Map.of());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.StepStatusChanged ev = (WorkflowEvents.StepStatusChanged) captor.getValue();
        assertThat(ev.iterationIndex()).isNull();
    }

    @Test
    void runCompleted_publishesWithStatusAndDuration() {
        UUID runId = UUID.randomUUID();

        publisher.runCompleted(runId, null, "completed", 1234L);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(springPublisher).publishEvent(captor.capture());
        WorkflowEvents.RunCompleted ev = (WorkflowEvents.RunCompleted) captor.getValue();
        assertThat(ev.status()).isEqualTo("completed");
        assertThat(ev.durationMs()).isEqualTo(1234L);
    }
}
