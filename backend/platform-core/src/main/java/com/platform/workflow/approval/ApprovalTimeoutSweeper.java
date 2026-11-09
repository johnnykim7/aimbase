package com.platform.workflow.approval;

import com.platform.domain.PendingApprovalEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import com.platform.workflow.event.WorkflowEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * CR-174 PRD-364, BIZ-144: 승인 시한이 지난 HUMAN_INPUT 대기를 닫는다.
 *
 * <p>HUMAN_INPUT 단계의 timeoutMs 는 pending_approvals.timeout_at 에 적히기만 했고 지나도 보는 곳이 없었다.
 * 여기서 캐시된 테넌트를 돌며 지난 대기를 timeout 으로 닫고, 그 run 을 failed(approval_timeout)로 끝내
 * 종료 신호(RunCompleted)를 낸다. 그래야 실행 종료 회신(_callback)과 실행 구독이 시한 초과를 안다.
 * 승인 API 가 먼저 pending 을 바꾼 줄은 조회에 걸리지 않고, run 이 이미 pending_approval 이 아니면 건드리지 않는다.
 */
@Component
public class ApprovalTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutSweeper.class);

    public static final String STATUS_TIMEOUT = "timeout";
    public static final String REASON_TIMEOUT = "approval_timeout";

    private final PendingApprovalRepository approvals;
    private final WorkflowRunRepository runs;
    private final WorkflowEventPublisher eventPublisher;
    private final TenantDataSourceManager tenantDataSourceManager;
    private final Clock clock;

    @Autowired
    public ApprovalTimeoutSweeper(PendingApprovalRepository approvals, WorkflowRunRepository runs,
                                  WorkflowEventPublisher eventPublisher,
                                  TenantDataSourceManager tenantDataSourceManager) {
        this(approvals, runs, eventPublisher, tenantDataSourceManager, Clock.systemUTC());
    }

    ApprovalTimeoutSweeper(PendingApprovalRepository approvals, WorkflowRunRepository runs,
                           WorkflowEventPublisher eventPublisher,
                           TenantDataSourceManager tenantDataSourceManager, Clock clock) {
        this.approvals = approvals;
        this.runs = runs;
        this.eventPublisher = eventPublisher;
        this.tenantDataSourceManager = tenantDataSourceManager;
        this.clock = clock;
    }

    /** CR-122 와 같이 캐시된 테넌트를 순회한다. pending_approvals 는 테넌트 테이블이라 컨텍스트 없이는 master 로 간다. */
    @Scheduled(fixedDelayString = "${aimbase.workflow.approval-timeout.sweep-ms:60000}",
               initialDelayString = "${aimbase.workflow.approval-timeout.initial-delay-ms:30000}")
    public void sweep() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                int closed = sweepCurrentTenant();
                if (closed > 0) {
                    log.info("CR-174 tenant {} 승인 시한 초과 {}건 닫음", tenantId, closed);
                }
            } catch (Exception e) {
                log.warn("CR-174 tenant {} 승인 시한 감시 실패: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 현재 테넌트 컨텍스트에서 시한이 지난 대기를 닫는다. 닫은 수를 돌려준다. */
    public int sweepCurrentTenant() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PendingApprovalEntity> expired =
                approvals.findByStatusAndApprovalChannelAndTimeoutAtBefore("pending", "workflow", now);
        int closed = 0;
        for (PendingApprovalEntity approval : expired) {
            approval.setStatus(STATUS_TIMEOUT);
            approval.setResolvedAt(now);
            approvals.save(approval);
            closed++;
            if (approval.getActionLogId() == null) {
                continue;
            }
            runs.findById(approval.getActionLogId())
                    .filter(run -> "pending_approval".equals(run.getStatus()))
                    .ifPresent(run -> failRun(run, approval, now));
        }
        return closed;
    }

    private void failRun(WorkflowRunEntity run, PendingApprovalEntity approval, OffsetDateTime now) {
        run.setStatus("failed");
        run.setError(Map.of(
                "reason", REASON_TIMEOUT,
                "detail", approval.getTimeoutAt() != null ? approval.getTimeoutAt().toString() : ""));
        run.setCompletedAt(now);
        WorkflowRunEntity saved = runs.save(run);
        long durationMs = saved.getStartedAt() != null
                ? now.toInstant().toEpochMilli() - saved.getStartedAt().toInstant().toEpochMilli()
                : 0L;
        log.info("CR-174 run {} 승인 시한 초과(시한 {}). failed 로 닫음", saved.getId(), approval.getTimeoutAt());
        if (eventPublisher != null) {
            eventPublisher.runCompleted(saved.getId(), saved.getParentRunId(), "failed", durationMs);
        }
    }
}
