CREATE TABLE workflows (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    domain          VARCHAR(50),
    trigger_config  JSONB        NOT NULL,
    steps           JSONB        NOT NULL,
    error_handling  JSONB,
    is_active       BOOLEAN      DEFAULT true,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_workflows_domain ON workflows(domain);
CREATE INDEX idx_workflows_active ON workflows(is_active);
