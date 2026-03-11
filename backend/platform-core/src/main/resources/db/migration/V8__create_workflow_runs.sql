CREATE TABLE workflow_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id     VARCHAR(100) REFERENCES workflows(id),
    session_id      VARCHAR(100),
    status          VARCHAR(20)  NOT NULL,
    current_step    VARCHAR(100),
    step_results    JSONB,
    input_data      JSONB,
    error           JSONB,
    started_at      TIMESTAMPTZ  DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_workflow_runs_workflow_id ON workflow_runs(workflow_id);
CREATE INDEX idx_workflow_runs_session_id ON workflow_runs(session_id);
CREATE INDEX idx_workflow_runs_status ON workflow_runs(status);
CREATE INDEX idx_workflow_runs_started ON workflow_runs(started_at DESC);
