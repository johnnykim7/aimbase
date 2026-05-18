package com.platform.workflow.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-058: WorkflowRunSubscriberRegistry 필터링/fan-out 검증.
 *
 * <p>SseEmitter 내부가 공개되지 않아 payload 구조는 여기서 직접 검증하지 않고,
 * 1) 매칭되는 subscriber 에게만 send 호출이 이뤄지는지,
 * 2) 부모-자식 런의 이벤트 라우팅 규칙이 맞는지만 검증한다.
 * payload 포맷 자체는 {@link WorkflowEventPublisherTest} 와 registry 구현의 정적 형태로 커버.
 */
class WorkflowRunSubscriberRegistryTest {

    private WorkflowRunSubscriberRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRunSubscriberRegistry();
    }

    @Test
    void fanOut_sendsEventToMatchingRunSubscriber() {
        UUID runId = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        registry.register(runId, emitter);

        registry.onStepStatus(new WorkflowEvents.StepStatusChanged(
                runId, null, "s1", "running", Instant.now(),
                null, null, null, null, null, null));

        assertThat(emitter.sendCount).isEqualTo(1);
    }

    @Test
    void fanOut_ignoresEventForDifferentRun() {
        UUID runId = UUID.randomUUID();
        UUID otherRun = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        registry.register(runId, emitter);

        registry.onStepStatus(new WorkflowEvents.StepStatusChanged(
                otherRun, null, "s1", "running", Instant.now(),
                null, null, null, null, null, null));

        assertThat(emitter.sendCount).isZero();
    }

    @Test
    void fanOut_forwardsChildEventToParentSubscriber() {
        UUID parentRun = UUID.randomUUID();
        UUID childRun = UUID.randomUUID();
        RecordingEmitter parentEmitter = new RecordingEmitter();
        registry.register(parentRun, parentEmitter);

        registry.onStepStatus(new WorkflowEvents.StepStatusChanged(
                childRun, parentRun, "sub_step_1", "completed",
                Instant.now(), Instant.now(), 10L, null, null, null, null));

        assertThat(parentEmitter.sendCount).isEqualTo(1);
    }

    @Test
    void fanOut_childAndParentSubscribersBothReceive() {
        UUID parentRun = UUID.randomUUID();
        UUID childRun = UUID.randomUUID();
        RecordingEmitter parentEmitter = new RecordingEmitter();
        RecordingEmitter childEmitter = new RecordingEmitter();
        registry.register(parentRun, parentEmitter);
        registry.register(childRun, childEmitter);

        registry.onStepStatus(new WorkflowEvents.StepStatusChanged(
                childRun, parentRun, "s", "running", Instant.now(),
                null, null, null, null, null, null));

        assertThat(parentEmitter.sendCount).isEqualTo(1);
        assertThat(childEmitter.sendCount).isEqualTo(1);
    }

    @Test
    void approval_deliveredToSubscriber() {
        UUID runId = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        registry.register(runId, emitter);

        registry.onApproval(new WorkflowEvents.ApprovalRequired(
                runId, null, "confirm", "policy1", "reason",
                List.of("u@x.com"), null));

        assertThat(emitter.sendCount).isEqualTo(1);
    }

    @Test
    void runCompleted_deliveredToSubscriber() {
        UUID runId = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter();
        registry.register(runId, emitter);

        registry.onRunCompleted(new WorkflowEvents.RunCompleted(runId, null, "completed", 1234L));

        assertThat(emitter.sendCount).isEqualTo(1);
    }

    @Test
    void subscriberCount_tracksRegistrations() {
        UUID runId = UUID.randomUUID();
        assertThat(registry.subscriberCount(runId)).isZero();

        registry.register(runId, new RecordingEmitter());
        registry.register(runId, new RecordingEmitter());

        assertThat(registry.subscriberCount(runId)).isEqualTo(2);
    }

    @Test
    void failedSend_removesEmitter() {
        UUID runId = UUID.randomUUID();
        FailingEmitter emitter = new FailingEmitter();
        registry.register(runId, emitter);
        assertThat(registry.subscriberCount(runId)).isEqualTo(1);

        registry.onRunCompleted(new WorkflowEvents.RunCompleted(runId, null, "failed", 100L));

        assertThat(registry.subscriberCount(runId)).isZero();
    }

    private static class RecordingEmitter extends SseEmitter {
        int sendCount = 0;
        RecordingEmitter() { super(30_000L); }
        @Override public void send(@NonNull SseEventBuilder builder) throws IOException { sendCount++; }
    }

    private static class FailingEmitter extends SseEmitter {
        FailingEmitter() { super(30_000L); }
        @Override public void send(@NonNull SseEventBuilder builder) throws IOException {
            throw new IOException("broken pipe");
        }
    }
}
