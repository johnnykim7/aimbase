CREATE TABLE mcp_servers (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    transport       VARCHAR(20)  NOT NULL,
    config          JSONB        NOT NULL,
    auto_start      BOOLEAN      DEFAULT true,
    status          VARCHAR(20)  DEFAULT 'disconnected',
    tools_cache     JSONB,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW()
);
