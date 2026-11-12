package com.platform.orchestrator.approval;

import com.platform.auth.UserPrincipal;
import com.platform.domain.PendingApprovalEntity;
import com.platform.llm.adapter.RequestContext;
import com.platform.policy.AuditLogger;
import com.platform.repository.PendingApprovalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** CR-185 TC-CHT-114, TC-CHT-117: 대화 승인 결정과 위젯의 워크플로우 승인 막기. */
class ApprovalDecisionServiceTest {

    private PendingApprovalRepository approvals;
    private ChatTurnRegistry turns;
    private AuditLogger audit;
    private ApprovalDecisionService service;
    private PendingApprovalEntity row;

    @BeforeEach
    void setUp() {
        approvals = mock(PendingApprovalRepository.class);
        turns = mock(ChatTurnRegistry.class);
        audit = mock(AuditLogger.class);
        service = new ApprovalDecisionService(approvals, turns, audit);
        row = new PendingApprovalEntity();
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        row.setApprovalChannel("chat");
        row.setRequestedBy("user-7");
        row.setSessionId("sess-1");
        row.setToolName("send_mail");
        when(approvals.findById(row.getId())).thenReturn(Optional.of(row));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    private void loginAs(String id, String role, String... scopes) {
        UserPrincipal p = new UserPrincipal(id, id, "t1", role, Map.of());
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(scopes)
                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, auths));
        if ("WIDGET".equals(role)) RequestContext.setUserRef(id);
    }

    private HttpStatus statusOf(Runnable r) {
        try {
            r.run();
            return HttpStatus.OK;
        } catch (ApprovalDecisionException e) {
            return e.getStatus();
        }
    }

    @Test
    void 요청한_사람이_승인하면_닫고_기다리는_관문을_깨운다() {
        loginAs("user-7", "WIDGET", "chat:stream");
        when(approvals.closeIfPending(eq(row.getId()), eq("approved"), eq("user-7"), any(), any())).thenReturn(1);

        ApprovalDecisionService.Decided d = service.decideChat(row.getId().toString(), "approve");

        assertThat(d.status()).isEqualTo("approved");
        assertThat(d.decidedBy()).isEqualTo("user-7");
        verify(turns).resolve(row.getId(), "approved");
        verify(audit).log(eq("APPROVAL_DECIDED"), eq(row.getId().toString()), eq("user-7"), eq("sess-1"), anyMap(), any());
    }

    @Test
    void TC_CHT_114_남의_승인은_403_not_requester() {
        loginAs("user-9", "WIDGET", "chat:stream");
        assertThatThrownBy(() -> service.decideChat(row.getId().toString(), "approve"))
                .isInstanceOf(ApprovalDecisionException.class).hasMessage("not_requester");
        verify(approvals, never()).closeIfPending(any(), any(), any(), any(), any());
    }

    @Test
    void TC_CHT_114_관리자는_남의_승인도_정한다() {
        loginAs("admin-1", "ADMIN");
        when(approvals.closeIfPending(eq(row.getId()), eq("rejected"), eq("admin-1"), any(), any())).thenReturn(1);
        assertThat(service.decideChat(row.getId().toString(), "reject").status()).isEqualTo("rejected");
    }

    @Test
    void TC_CHT_114_없는_번호와_잘못된_번호는_404() {
        loginAs("user-7", "WIDGET");
        assertThatThrownBy(() -> service.decideChat(UUID.randomUUID().toString(), "approve"))
                .hasMessage("approval_not_found");
        assertThatThrownBy(() -> service.decideChat("not-a-uuid", "approve"))
                .hasMessage("approval_not_found");
    }

    @Test
    void TC_CHT_114_워크플로우_줄은_400_not_chat_approval() {
        loginAs("user-7", "WIDGET");
        row.setApprovalChannel("workflow");
        assertThatThrownBy(() -> service.decideChat(row.getId().toString(), "approve"))
                .hasMessage("not_chat_approval");
    }

    @Test
    void TC_CHT_114_decision_오타는_400_invalid_decision() {
        loginAs("user-7", "WIDGET");
        assertThatThrownBy(() -> service.decideChat(row.getId().toString(), "approved"))
                .hasMessage("invalid_decision");
    }

    @Test
    void TC_CHT_114_이미_정해진_줄은_409_와_지금_상태() {
        loginAs("user-7", "WIDGET");
        row.setStatus("timeout");
        when(approvals.closeIfPending(any(), any(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.decideChat(row.getId().toString(), "approve"))
                .isInstanceOfSatisfying(ApprovalDecisionException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("approval_closed");
                    assertThat(e.getCurrentStatus()).isEqualTo("timeout");
                });
        verify(turns, never()).resolve(any(), any());
    }

    @Test
    void TC_CHT_114_동시_두_결정은_하나만_지난다() {
        loginAs("user-7", "WIDGET");
        when(approvals.closeIfPending(any(), any(), any(), any(), any())).thenReturn(1, 0);
        HttpStatus first = statusOf(() -> service.decideChat(row.getId().toString(), "approve"));
        HttpStatus second = statusOf(() -> service.decideChat(row.getId().toString(), "reject"));
        assertThat(List.of(first, second)).containsExactly(HttpStatus.OK, HttpStatus.CONFLICT);
    }

    @Test
    void TC_CHT_117_위젯_토큰은_workflow_approve_권한이_있어야_한다() {
        loginAs("user-7", "WIDGET", "chat:stream");
        assertThatThrownBy(() -> service.authorizeWorkflowDecision(UUID.randomUUID()))
                .hasMessage("scope_required");
    }

    @Test
    void TC_CHT_117_승인자_목록_밖이면_403_안이면_통과() {
        UUID runId = UUID.randomUUID();
        PendingApprovalEntity wf = new PendingApprovalEntity();
        wf.setApprovalChannel("workflow");
        wf.setApprovers(List.of("lead-1"));
        when(approvals.findByActionLogId(runId)).thenReturn(List.of(wf));

        loginAs("user-7", "WIDGET", "workflow:approve");
        assertThatThrownBy(() -> service.authorizeWorkflowDecision(runId)).hasMessage("not_approver");

        loginAs("lead-1", "WIDGET", "workflow:approve");
        service.authorizeWorkflowDecision(runId);
    }

    @Test
    void TC_CHT_117_로그인_토큰은_지금처럼_정한다() {
        loginAs("admin-1", "ADMIN");
        service.authorizeWorkflowDecision(UUID.randomUUID());
        verifyNoInteractions(approvals);
    }
}
