package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "pending_approvals")
public class PendingApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "action_log_id")
    private UUID actionLogId;

    @Column(name = "policy_id", length = 100)
    private String policyId;

    @Column(name = "approval_channel", length = 100)
    private String approvalChannel;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<String> approvers;

    @Column(length = 20)
    private String status = "pending";

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "timeout_at")
    private OffsetDateTime timeoutAt;

    public UUID getId() { return id; }
    public UUID getActionLogId() { return actionLogId; }
    public void setActionLogId(UUID actionLogId) { this.actionLogId = actionLogId; }
    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }
    public String getApprovalChannel() { return approvalChannel; }
    public void setApprovalChannel(String approvalChannel) { this.approvalChannel = approvalChannel; }
    public List<String> getApprovers() { return approvers; }
    public void setApprovers(List<String> approvers) { this.approvers = approvers; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public OffsetDateTime getTimeoutAt() { return timeoutAt; }
    public void setTimeoutAt(OffsetDateTime timeoutAt) { this.timeoutAt = timeoutAt; }
}
