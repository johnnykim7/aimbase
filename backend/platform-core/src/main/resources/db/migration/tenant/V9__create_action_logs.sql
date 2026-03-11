CREATE TABLE action_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_run_id UUID REFERENCES workflow_runs(id),
    session_id      VARCHAR(100),
    intent          VARCHAR(100) NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    adapter         VARCHAR(50)  NOT NULL,
    destination     VARCHAR(200),
    payload         JSONB,
    policy_result   JSONB,
    status          VARCHAR(20)  NOT NULL,
    result          JSONB,
    error           JSONB,
    executed_at     TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_action_logs_session_id ON action_logs(session_id);
CREATE INDEX idx_action_logs_intent ON action_logs(intent);
CREATE INDEX idx_action_logs_status ON action_logs(status);
CREATE INDEX idx_action_logs_executed ON action_logs(executed_at DESC);
