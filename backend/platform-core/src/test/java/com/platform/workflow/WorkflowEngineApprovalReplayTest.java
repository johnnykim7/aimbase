package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.PendingApprovalEntity;
import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.event.WorkflowEventPublisher;
import com.platform.workflow.event.WorkflowEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEngineApprovalReplayTest {

    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository runRepository;
    private PendingApprovalRepository approvalRepository;
    private WorkflowEventPublisher publisher;
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
        when(runRepository.save(any())).thenAnswer(inv -> {
            WorkflowRunEntity r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        engine = new WorkflowEngine(
                workflowRepository, runRepository, approvalRepository, new ObjectMapper(), List.of(),
                mock(PlatformMetrics.class), publisher, null, null, null,
                provider(mock(com.platform.agent.ActiveCliWorkerRegistry.class)),
                provider(mock(com.platform.llm.ConnectionAdapterFactory.class)),
                provider(new WorkflowCancellationRegistry()), null, null, provider(null));
    }

    private static PendingApprovalEntity approval(UUID runId, String step, String channel, String status, String message) {
        PendingApprovalEntity a = new PendingApprovalEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setActionLogId(runId);
        a.setPolicyId(step);
        a.setApprovalChannel(channel);
        a.setStatus(status);
        a.setRequestMessage(message);
        return a;
    }

    @Test
    @DisplayName("TC-CHT-119 아직 정해지지 않은 워크플로우 승인만 실시간 알림과 같은 모양으로 돌려준다")
    void pendingApprovalEventsReturnsOnlyOpenWorkflowApprovals() {
        UUID runId = UUID.randomUUID();
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(runId);
        PendingApprovalEntity open = approval(runId, "h1", "workflow", "pending", "출고 전 확인");
        open.setApprovers(List.of("lead"));
        OffsetDateTime due = OffsetDateTime.now().plusMinutes(5);
        open.setTimeoutAt(due);
        when(approvalRepository.findByActionLogId(runId)).thenReturn(List.of(
                approval(runId, "h0", "workflow", "approved", "앞 단계"),
                approval(runId, "tool", "chat", "pending", "대화 승인"),
                open));

        List<WorkflowEvents.ApprovalRequired> events = engine.pendingApprovalEvents(run);

        assertThat(events).hasSize(1);
        WorkflowEvents.ApprovalRequired ev = events.get(0);
        assertThat(ev.runId()).isEqualTo(runId);
        assertThat(ev.stepId()).isEqualTo("h1");
        assertThat(ev.policyId()).isEqualTo("h1");
        assertThat(ev.reason()).isEqualTo("출고 전 확인");
        assertThat(ev.approvers()).containsExactly("lead");
        assertThat(ev.timeoutAt()).isEqualTo(due.toInstant());
        assertThat(ev.approvalId()).isEqualTo(open.getId());
    }

    @Test
    @DisplayName("TC-CHT-119 승인 대기에 들어가면 단계의 안내 문구를 승인 줄에 남기고 알림에 승인 번호를 싣는다")
    void humanInputStoresReasonAndPublishesApprovalId() {
        WorkflowEntity w = new WorkflowEntity();
        w.setId("wf");
        w.setSteps(List.of(Map.of("id", "h1", "name", "확인", "type", "HUMAN_INPUT",
                "config", Map.of("reason", "출고 전 확인"))));
        when(workflowRepository.findByIdAndIsActiveTrue("wf")).thenReturn(Optional.of(w));
        UUID approvalId = UUID.randomUUID();
        when(approvalRepository.save(any())).thenAnswer(inv -> {
            PendingApprovalEntity a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", approvalId);
            return a;
        });

        WorkflowRunEntity run = engine.execute("wf", Map.of(), null);

        ArgumentCaptor<PendingApprovalEntity> saved = ArgumentCaptor.forClass(PendingApprovalEntity.class);
        // 실행은 비동기라 승인 줄 저장과 알림은 조금 뒤에 온다
        verify(approvalRepository, timeout(5000)).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isEqualTo("출고 전 확인");
        verify(publisher, timeout(5000)).approvalRequired(eq(run.getId()), isNull(), eq("h1"), eq("h1"),
                eq("출고 전 확인"), eq(List.of()), isNull(), eq(approvalId));
    }
}
