CREATE TABLE connections (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    adapter         VARCHAR(50)  NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    config          JSONB        NOT NULL,
    status          VARCHAR(20)  DEFAULT 'disconnected',
    health_config   JSONB,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_connections_type ON connections(type);
CREATE INDEX idx_connections_adapter ON connections(adapter);
