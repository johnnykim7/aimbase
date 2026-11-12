package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.domain.PendingApprovalEntity;
import com.platform.monitoring.TraceService;
import com.platform.orchestrator.approval.ApprovalDecisionService;
import com.platform.policy.PolicyEngine;
import com.platform.repository.ActionLogRepository;
import com.platform.repository.AuditLogRepository;
import com.platform.repository.ConnectionRepository;
import com.platform.repository.ModelPricingRepository;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.PolicyRepository;
import com.platform.repository.UsageLogRepository;
import com.platform.session.ChatRejectedException;
import com.platform.workflow.WorkflowEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** CR-185 TC-CHT-115(정책 저장), TC-CHT-118(관리자 승인). */
class Cr185ApprovalControllersTest {

    private PendingApprovalRepository approvals;
    private WorkflowEngine workflowEngine;
    private ApprovalDecisionService decisions;
    private AdminController admin;

    @BeforeEach
    void setUp() {
        approvals = mock(PendingApprovalRepository.class);
        workflowEngine = mock(WorkflowEngine.class);
        decisions = mock(ApprovalDecisionService.class);
        admin = new AdminController(mock(ActionLogRepository.class), mock(AuditLogRepository.class),
                mock(UsageLogRepository.class), approvals, mock(ConnectionRepository.class),
                mock(ModelPricingRepository.class), workflowEngine, mock(TraceService.class), decisions);
        UserPrincipal p = new UserPrincipal("admin-1", "admin-1", "t1", "ADMIN", Map.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PendingApprovalEntity row(String channel) {
        PendingApprovalEntity a = new PendingApprovalEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setApprovalChannel(channel);
        when(approvals.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void 정한_사람은_본문이_아니라_로그인_주체로_남긴다() {
        PendingApprovalEntity a = row("workflow");
        a.setActionLogId(UUID.randomUUID());
        admin.approve(a.getId(), Map.of("approved_by", "someone-else"));
        verify(workflowEngine).resume(a.getActionLogId(), true, null, "admin-1");
    }

    @Test
    void 대화_승인은_같은_결정_처리를_탄다() {
        PendingApprovalEntity a = row("chat");
        admin.reject(a.getId(), Map.of("reason", "범위 밖"));
        verify(decisions).decideChatAsAdmin(a, false, "범위 밖");
        verify(approvals, never()).closeIfPending(any(), any(), any(), any(), any());
    }

    @Test
    void 이어_갈_run_이_없는_줄은_줄만_닫고_이미_닫혔으면_409() {
        PendingApprovalEntity a = row("other");
        when(approvals.closeIfPending(eq(a.getId()), eq("approved"), eq("admin-1"), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> admin.approve(a.getId(), null)).hasMessage("approval_closed");
    }

    @Test
    void 정책_tools_가_빈_목록이면_저장하지_않는다() {
        PolicyController policies = new PolicyController(mock(PolicyRepository.class), mock(PolicyEngine.class));
        PolicyController.PolicyRequest empty = new PolicyController.PolicyRequest(
                "p1", "메일 확인", null, 10, Map.of(PolicyEngine.MATCH_TOOLS, List.of()),
                List.of(Map.of("type", "REQUIRE_APPROVAL")));
        assertThatThrownBy(() -> policies.create(empty))
                .isInstanceOf(ChatRejectedException.class).hasMessage("policy_tools_empty");
        PolicyController.PolicyRequest notList = new PolicyController.PolicyRequest(
                "p1", "메일 확인", null, 10, Map.of(PolicyEngine.MATCH_TOOLS, "send_mail"),
                List.of(Map.of("type", "REQUIRE_APPROVAL")));
        assertThatThrownBy(() -> policies.create(notList)).hasMessage("policy_tools_empty");
    }
}
