CREATE TABLE pending_approvals (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_log_id    UUID REFERENCES action_logs(id),
    policy_id        VARCHAR(100) REFERENCES policies(id),
    approval_channel VARCHAR(100),
    approvers        JSONB,
    status           VARCHAR(20)  DEFAULT 'pending',
    approved_by      VARCHAR(100),
    reason           TEXT,
    requested_at     TIMESTAMPTZ  DEFAULT NOW(),
    resolved_at      TIMESTAMPTZ,
    timeout_at       TIMESTAMPTZ
);

CREATE INDEX idx_pending_approvals_status ON pending_approvals(status);
CREATE INDEX idx_pending_approvals_requested ON pending_approvals(requested_at DESC);
