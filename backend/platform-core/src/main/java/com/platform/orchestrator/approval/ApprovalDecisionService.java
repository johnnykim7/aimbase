package com.platform.orchestrator.approval;

import com.platform.auth.UserPrincipal;
import com.platform.domain.PendingApprovalEntity;
import com.platform.llm.adapter.RequestContext;
import com.platform.policy.AuditLogger;
import com.platform.repository.PendingApprovalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-185 PRD-560, PRD-562, BIZ-243, BIZ-245: 대화 승인을 정한다. 위젯 결정 API 와 관리자 승인 API 가 같이 쓴다.
 *
 * <p>pending 일 때만 바뀌는 한 번의 갱신으로 닫아 둘이 함께 눌러도 하나만 지난다. 닫은 뒤 기다리는 도구 관문을 깨운다.
 */
@Service
public class ApprovalDecisionService {

    public static final String AUDIT_ACTION = "APPROVAL_DECIDED";
    static final String ROLE_WIDGET = "WIDGET";
    static final String SCOPE_WORKFLOW_APPROVE = "SCOPE_workflow:approve";

    public record Decided(UUID approvalId, String status, String decidedBy, OffsetDateTime decidedAt) {
        public Map<String, Object> toResponse() {
            Map<String, Object> out = new HashMap<>();
            out.put("approval_id", approvalId.toString());
            out.put("status", status);
            out.put("decided_by", decidedBy);
            out.put("decided_at", decidedAt.toString());
            return out;
        }
    }

    private final PendingApprovalRepository approvals;
    private final ChatTurnRegistry turns;
    private final AuditLogger auditLogger;
    private final Clock clock;

    @Autowired
    public ApprovalDecisionService(PendingApprovalRepository approvals, ChatTurnRegistry turns,
                                   AuditLogger auditLogger) {
        this(approvals, turns, auditLogger, Clock.systemUTC());
    }

    ApprovalDecisionService(PendingApprovalRepository approvals, ChatTurnRegistry turns,
                            AuditLogger auditLogger, Clock clock) {
        this.approvals = approvals;
        this.turns = turns;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    /** 위젯 결정 API. 요청한 사람 본인이나 관리자만 정한다. */
    public Decided decideChat(String approvalId, String decision) {
        String status = statusOf(decision);
        PendingApprovalEntity row = findChat(approvalId);
        String actor = ChatTurnRegistry.currentActor();
        if (!isAdmin() && (actor == null || !actor.equals(row.getRequestedBy()))) {
            throw new ApprovalDecisionException(HttpStatus.FORBIDDEN, "not_requester");
        }
        return close(row, status, actor, null);
    }

    /** 관리자 승인 API. 로그인 주체가 정한 사람이다. */
    public Decided decideChatAsAdmin(PendingApprovalEntity row, boolean approve, String reason) {
        return close(row, approve ? ChatTurnRegistry.APPROVED : ChatTurnRegistry.REJECTED,
                ChatTurnRegistry.currentActor(), reason);
    }

    /**
     * CR-185 BIZ-244: 위젯 토큰이 워크플로우 승인을 정해도 되는가. 로그인과 API 키는 지금처럼 통과한다.
     * 권한 workflow:approve 가 있어야 하고, 그 단계에 승인자 목록이 있으면 토큰 user_ref 가 목록에 있어야 한다.
     */
    public void authorizeWorkflowDecision(UUID runId) {
        if (!isWidget()) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean scoped = auth.getAuthorities().stream()
                .anyMatch(a -> SCOPE_WORKFLOW_APPROVE.equals(a.getAuthority()));
        if (!scoped) throw new ApprovalDecisionException(HttpStatus.FORBIDDEN, "scope_required");
        List<String> approvers = approvals.findByActionLogId(runId).stream()
                .filter(a -> "pending".equals(a.getStatus()) && a.getApprovers() != null)
                .flatMap(a -> a.getApprovers().stream())
                .toList();
        String userRef = RequestContext.getUserRef();
        if (!approvers.isEmpty() && (userRef == null || !approvers.contains(userRef))) {
            throw new ApprovalDecisionException(HttpStatus.FORBIDDEN, "not_approver");
        }
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        if (auth.getPrincipal() instanceof UserPrincipal p) return !ROLE_WIDGET.equalsIgnoreCase(p.getRole());
        return false;
    }

    public static boolean isWidget() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof UserPrincipal p
                && ROLE_WIDGET.equalsIgnoreCase(p.getRole());
    }

    private static String statusOf(String decision) {
        if ("approve".equals(decision)) return ChatTurnRegistry.APPROVED;
        if ("reject".equals(decision)) return ChatTurnRegistry.REJECTED;
        throw new ApprovalDecisionException(HttpStatus.BAD_REQUEST, "invalid_decision");
    }

    private PendingApprovalEntity findChat(String approvalId) {
        UUID id;
        try {
            id = UUID.fromString(approvalId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApprovalDecisionException(HttpStatus.NOT_FOUND, "approval_not_found");
        }
        PendingApprovalEntity row = approvals.findById(id)
                .orElseThrow(() -> new ApprovalDecisionException(HttpStatus.NOT_FOUND, "approval_not_found"));
        if (!ToolApprovalGate.CHANNEL_CHAT.equals(row.getApprovalChannel())) {
            throw new ApprovalDecisionException(HttpStatus.BAD_REQUEST, "not_chat_approval");
        }
        return row;
    }

    private Decided close(PendingApprovalEntity row, String status, String actor, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int closed = approvals.closeIfPending(row.getId(), status, actor, reason, now);
        if (closed == 0) {
            String current = approvals.findById(row.getId()).map(PendingApprovalEntity::getStatus).orElse(null);
            throw new ApprovalDecisionException(HttpStatus.CONFLICT, "approval_closed", current);
        }
        turns.resolve(row.getId(), status);
        Map<String, Object> detail = new HashMap<>();
        detail.put("channel", ToolApprovalGate.CHANNEL_CHAT);
        detail.put("status", status);
        detail.put("tool", row.getToolName());
        if (reason != null) detail.put("reason", reason);
        auditLogger.log(AUDIT_ACTION, row.getId().toString(), actor, row.getSessionId(), detail, null);
        return new Decided(row.getId(), status, actor, now);
    }
}
