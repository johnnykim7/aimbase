CREATE TABLE prompts (
    id              VARCHAR(100) NOT NULL,
    version         INTEGER      NOT NULL DEFAULT 1,
    domain          VARCHAR(50),
    type            VARCHAR(30)  NOT NULL,
    template        TEXT         NOT NULL,
    variables       JSONB,
    is_active       BOOLEAN      DEFAULT false,
    ab_test         JSONB,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    PRIMARY KEY (id, version)
);

CREATE INDEX idx_prompts_domain ON prompts(domain);
CREATE INDEX idx_prompts_active ON prompts(is_active);
