CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(100),
    session_id      VARCHAR(100),
    action          VARCHAR(50)  NOT NULL,
    target          VARCHAR(200),
    detail          JSONB,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_created ON audit_logs(created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
