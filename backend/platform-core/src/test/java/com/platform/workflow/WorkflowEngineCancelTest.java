package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.PendingApprovalEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.event.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 워크플로우 협조적 중지 단위 테스트.
 *
 * <p>{@link WorkflowEngine#cancelRun(UUID)} 의 상태별 분기를 고정한다.
 * 실제 실행 루프(VT)는 IT 영역이라 여기서는 컨트롤러 진입점의 분기 로직만 검증한다:
 * <ul>
 *   <li>running → 표식만 세움(즉시 종료 아님, 이벤트/메트릭 미발행)</li>
 *   <li>pending_approval → 즉시 cancelled 전이 + 승인 엔티티 정리 + 이벤트/메트릭 발행</li>
 *   <li>terminal(completed/failed/cancelled) → 멱등, 변경 없음</li>
 *   <li>미존재 → IllegalArgumentException</li>
 * </ul>
 */
@DisplayName("WorkflowEngine — 협조적 중지 cancelRun")
class WorkflowEngineCancelTest {

    private WorkflowRunRepository runRepository;
    private PendingApprovalRepository approvalRepository;
    private PlatformMetrics metrics;
    private WorkflowEventPublisher eventPublisher;
    private com.platform.agent.ActiveCliWorkerRegistry workerRegistry;
    private com.platform.llm.ConnectionAdapterFactory adapterFactory;
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
        runRepository = mock(WorkflowRunRepository.class);
        approvalRepository = mock(PendingApprovalRepository.class);
        metrics = mock(PlatformMetrics.class);
        eventPublisher = mock(WorkflowEventPublisher.class);
        workerRegistry = mock(com.platform.agent.ActiveCliWorkerRegistry.class);
        adapterFactory = mock(com.platform.llm.ConnectionAdapterFactory.class);
        engine = new WorkflowEngine(
                mock(WorkflowRepository.class),
                runRepository,
                approvalRepository,
                new ObjectMapper(),
                List.of(),
                metrics,
                eventPublisher,
                null,
                null,
                null,
                provider(workerRegistry),       // CR-116
                provider(adapterFactory));       // CR-116
    }

    private WorkflowRunEntity run(UUID id, String status) {
        WorkflowRunEntity r = new WorkflowRunEntity();
        r.setId(id);
        r.setWorkflowId("wf1");
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("미존재 run → IllegalArgumentException")
    void notFoundThrows() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> engine.cancelRun(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workflow run not found");
    }

    @Nested
    @DisplayName("running — 표식만 세우고 백그라운드 루프에 위임")
    class Running {
        @Test
        @DisplayName("status 즉시 변경 없음 + saveAndFlush 미호출 + 종료 이벤트 미발행")
        void marksOnlyDoesNotTerminate() {
            UUID id = UUID.randomUUID();
            WorkflowRunEntity r = run(id, "running");
            when(runRepository.findById(id)).thenReturn(Optional.of(r));

            WorkflowRunEntity result = engine.cancelRun(id);

            // running 은 백그라운드 루프가 처리 — cancelRun 자체는 상태를 바꾸지 않는다.
            assertThat(result.getStatus()).isEqualTo("running");
            verify(runRepository, never()).saveAndFlush(any());
            verify(eventPublisher, never()).runCompleted(any(), any(), eq("cancelled"), org.mockito.ArgumentMatchers.anyLong());
            verify(metrics, never()).recordWorkflowExecution("cancelled");
        }
    }

    @Nested
    @DisplayName("pending_approval — 즉시 cancelled 전이")
    class PendingApproval {
        @Test
        @DisplayName("status=cancelled 저장 + 종료 이벤트 + 메트릭 + 승인 엔티티 정리")
        void immediateCancel() {
            UUID id = UUID.randomUUID();
            WorkflowRunEntity r = run(id, "pending_approval");
            when(runRepository.findById(id)).thenReturn(Optional.of(r));
            when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            PendingApprovalEntity pending = new PendingApprovalEntity();
            pending.setActionLogId(id);
            pending.setStatus("pending");
            PendingApprovalEntity alreadyResolved = new PendingApprovalEntity();
            alreadyResolved.setActionLogId(id);
            alreadyResolved.setStatus("approved");
            when(approvalRepository.findByActionLogId(id))
                    .thenReturn(List.of(pending, alreadyResolved));

            WorkflowRunEntity result = engine.cancelRun(id);

            assertThat(result.getStatus()).isEqualTo("cancelled");
            assertThat(result.getCompletedAt()).isNotNull();
            verify(runRepository).saveAndFlush(r);
            verify(metrics).recordWorkflowExecution("cancelled");
            verify(eventPublisher).runCompleted(eq(id), any(), eq("cancelled"), org.mockito.ArgumentMatchers.anyLong());

            // pending 만 cancelled 로 정리 (이미 approved 인 것은 그대로)
            assertThat(pending.getStatus()).isEqualTo("cancelled");
            assertThat(alreadyResolved.getStatus()).isEqualTo("approved");
            ArgumentCaptor<PendingApprovalEntity> captor = ArgumentCaptor.forClass(PendingApprovalEntity.class);
            verify(approvalRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(pending);
        }
    }

    @Nested
    @DisplayName("terminal — 멱등 (변경 없음)")
    class Terminal {
        @Test
        @DisplayName("completed → 무변경 반환")
        void completedNoop() {
            assertNoop("completed");
        }

        @Test
        @DisplayName("failed → 무변경 반환")
        void failedNoop() {
            assertNoop("failed");
        }

        @Test
        @DisplayName("cancelled → 무변경 반환 (재호출 멱등)")
        void cancelledNoop() {
            assertNoop("cancelled");
        }

        private void assertNoop(String status) {
            UUID id = UUID.randomUUID();
            WorkflowRunEntity r = run(id, status);
            when(runRepository.findById(id)).thenReturn(Optional.of(r));

            WorkflowRunEntity result = engine.cancelRun(id);

            assertThat(result.getStatus()).isEqualTo(status);
            verify(runRepository, never()).saveAndFlush(any());
            verify(metrics, never()).recordWorkflowExecution(any());
            verify(eventPublisher, never()).runCompleted(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        }
    }

    @Test
    @DisplayName("requestCancel — 중복 호출 시 두 번째는 false (멱등 표식)")
    void requestCancelIdempotent() {
        UUID id = UUID.randomUUID();
        assertThat(engine.requestCancel(id)).isTrue();
        assertThat(engine.requestCancel(id)).isFalse();
    }

    @Nested
    @DisplayName("CR-116 force=true — CLI worker 즉시 kill + 즉시 cancelled")
    class ForceCancel {
        @Test
        @DisplayName("running + force → worker.cleanupSession 호출 + status=cancelled 저장 + 이벤트/메트릭 발행")
        void forceKillsWorkerAndTerminates() {
            UUID id = UUID.randomUUID();
            WorkflowRunEntity r = run(id, "running");
            when(runRepository.findById(id)).thenReturn(Optional.of(r));
            when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            var w1 = new com.platform.agent.ActiveCliWorkerRegistry.WorkerRef("subagent-aaa", "conn-1");
            var w2 = new com.platform.agent.ActiveCliWorkerRegistry.WorkerRef("subagent-bbb", "conn-1");
            when(workerRegistry.workersOf(id.toString())).thenReturn(List.of(w1, w2));
            com.platform.llm.adapter.LLMAdapter adapter = mock(com.platform.llm.adapter.LLMAdapter.class);
            when(adapterFactory.getAdapter("conn-1")).thenReturn(adapter);

            WorkflowRunEntity result = engine.cancelRun(id, true);

            verify(adapter).cleanupSession("subagent-aaa");
            verify(adapter).cleanupSession("subagent-bbb");
            assertThat(result.getStatus()).isEqualTo("cancelled");
            assertThat(result.getCompletedAt()).isNotNull();
            verify(runRepository).saveAndFlush(r);
            verify(metrics).recordWorkflowExecution("cancelled");
            verify(eventPublisher).runCompleted(eq(id), any(), eq("cancelled"), org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("활성 worker 없어도 force → kill 호출 0건이지만 즉시 cancelled")
        void forceWithNoWorkersStillTerminates() {
            UUID id = UUID.randomUUID();
            WorkflowRunEntity r = run(id, "running");
            when(runRepository.findById(id)).thenReturn(Optional.of(r));
            when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workerRegistry.workersOf(id.toString())).thenReturn(List.of());

            WorkflowRunEntity result = engine.cancelRun(id, true);

            assertThat(result.getStatus()).isEqualTo("cancelled");
            verify(adapterFactory, never()).getAdapter(any());
            verify(eventPublisher).runCompleted(eq(id), any(), eq("cancelled"), org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("force=false(기본) → 협조적 동작 유지 (worker 조회/kill 안 함)")
        void nonForceStaysCooperative() {
            UUID id = UUID.randomUUID();
            WorkflowRunEntity r = run(id, "running");
            when(runRepository.findById(id)).thenReturn(Optional.of(r));

            WorkflowRunEntity result = engine.cancelRun(id, false);

            assertThat(result.getStatus()).isEqualTo("running");
            verify(workerRegistry, never()).workersOf(any());
            verify(runRepository, never()).saveAndFlush(any());
        }
    }
}
