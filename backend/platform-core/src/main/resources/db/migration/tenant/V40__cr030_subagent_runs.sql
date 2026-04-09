-- CR-030 Phase 6: subagent_runs 테이블 (PRD-207/210)
CREATE TABLE IF NOT EXISTS subagent_runs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_session_id VARCHAR(100) NOT NULL,
    child_session_id  VARCHAR(100),
    description       VARCHAR(200),
    prompt            TEXT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    isolation_mode    VARCHAR(20)  DEFAULT 'NONE',
    worktree_path     VARCHAR(500),
    branch_name       VARCHAR(200),
    base_commit       VARCHAR(50),
    exit_code         INTEGER      DEFAULT -1,
    output            TEXT,
    structured_data   JSONB,
    input_tokens      BIGINT       DEFAULT 0,
    output_tokens     BIGINT       DEFAULT 0,
    duration_ms       BIGINT       DEFAULT 0,
    error             TEXT,
    run_in_background BOOLEAN      DEFAULT FALSE,
    config            JSONB,
    timeout_ms        BIGINT       DEFAULT 120000,
    started_at        TIMESTAMPTZ  DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_subagent_runs_parent_session ON subagent_runs(parent_session_id);
CREATE INDEX IF NOT EXISTS idx_subagent_runs_status ON subagent_runs(status);
