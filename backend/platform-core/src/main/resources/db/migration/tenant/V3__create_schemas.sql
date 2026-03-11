CREATE TABLE schemas (
    id              VARCHAR(100) NOT NULL,
    version         INTEGER      NOT NULL DEFAULT 1,
    domain          VARCHAR(50),
    description     TEXT,
    json_schema     JSONB        NOT NULL,
    transforms      JSONB,
    validators      JSONB,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    PRIMARY KEY (id, version)
);
