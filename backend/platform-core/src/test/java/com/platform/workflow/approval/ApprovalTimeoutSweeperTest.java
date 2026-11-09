package com.platform.workflow.approval;

import com.platform.domain.PendingApprovalEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import com.platform.workflow.event.WorkflowEventPublisher;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("승인 시한 감시기 (CR-174, PRD-364, BIZ-144)")
class ApprovalTimeoutSweeperTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:12:00Z");

    private PendingApprovalRepository approvals;
    private WorkflowRunRepository runs;
    private WorkflowEventPublisher publisher;
    private TenantDataSourceManager tenants;
    private ApprovalTimeoutSweeper sweeper;

    @BeforeEach
    void setUp() {
        approvals = mock(PendingApprovalRepository.class);
        runs = mock(WorkflowRunRepository.class);
        publisher = mock(WorkflowEventPublisher.class);
        tenants = mock(TenantDataSourceManager.class);
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        sweeper = new ApprovalTimeoutSweeper(approvals, runs, publisher, tenants, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PendingApprovalEntity expired(UUID runId) {
        PendingApprovalEntity a = new PendingApprovalEntity();
        a.setActionLogId(runId);
        a.setApprovalChannel("workflow");
        a.setStatus("pending");
        a.setTimeoutAt(OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        when(approvals.findByStatusAndApprovalChannelAndTimeoutAtBefore(eq("pending"), eq("workflow"), any()))
                .thenReturn(List.of(a));
        return a;
    }

    private WorkflowRunEntity run(String status) {
        WorkflowRunEntity r = new WorkflowRunEntity();
        r.setId(UUID.randomUUID());
        r.setStatus(status);
        r.setStartedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC));
        when(runs.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("TC-WF-038 시한이 지난 대기는 timeout 으로 닫고 run 을 failed(approval_timeout)로 끝내 종료 신호를 낸다")
    void expiredApprovalFailsRun() {
        WorkflowRunEntity r = run("pending_approval");
        PendingApprovalEntity a = expired(r.getId());

        int closed = sweeper.sweepCurrentTenant();

        assertThat(closed).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo("timeout");
        assertThat(a.getResolvedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(approvals).save(a);
        assertThat(r.getStatus()).isEqualTo("failed");
        assertThat(r.getError()).containsEntry("reason", "approval_timeout")
                .containsEntry("detail", a.getTimeoutAt().toString());
        assertThat(r.getCompletedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(publisher).runCompleted(eq(r.getId()), isNull(), eq("failed"), eq(3600_000L));
    }

    @Test
    @DisplayName("TC-WF-040 승인 API 가 먼저 풀어 run 이 대기가 아니면 대기만 닫고 run 은 건드리지 않는다")
    void leavesRunThatIsNoLongerPending() {
        WorkflowRunEntity r = run("running");
        expired(r.getId());

        sweeper.sweepCurrentTenant();

        assertThat(r.getStatus()).isEqualTo("running");
        verify(runs, never()).save(any());
        verify(publisher, never()).runCompleted(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("TC-WF-039 시한 전과 시한 없음은 조회(pending, workflow, timeout_at 이 now 이전)에 걸리지 않고, 테넌트를 돌며 컨텍스트를 세운다")
    void sweepsEachTenantAndClearsContext() {
        when(tenants.getAllCachedDataSources()).thenReturn(Map.of("bp_3plgw", mock(HikariDataSource.class)));
        when(approvals.findByStatusAndApprovalChannelAndTimeoutAtBefore(any(), any(), any()))
                .thenAnswer(inv -> {
                    assertThat(TenantContext.getTenantId()).isEqualTo("bp_3plgw");
                    return List.of();
                });

        sweeper.sweep();

        verify(approvals).findByStatusAndApprovalChannelAndTimeoutAtBefore(eq("pending"), eq("workflow"),
                eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        verify(approvals, never()).save(any());
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
