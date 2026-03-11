CREATE TABLE routing_config (
    id              VARCHAR(100) PRIMARY KEY,
    strategy        VARCHAR(30)  NOT NULL,
    rules           JSONB        NOT NULL,
    fallback_chain  JSONB,
    is_active       BOOLEAN      DEFAULT true,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW()
);
