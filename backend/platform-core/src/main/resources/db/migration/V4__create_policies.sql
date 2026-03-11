CREATE TABLE policies (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    domain          VARCHAR(50),
    priority        INTEGER      NOT NULL DEFAULT 0,
    is_active       BOOLEAN      DEFAULT true,
    match_rules     JSONB        NOT NULL,
    rules           JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_policies_domain ON policies(domain);
CREATE INDEX idx_policies_priority ON policies(priority DESC);
CREATE INDEX idx_policies_active ON policies(is_active);
