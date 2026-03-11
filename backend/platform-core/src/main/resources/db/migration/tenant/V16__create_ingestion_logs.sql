CREATE TABLE ingestion_logs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id           VARCHAR(100) REFERENCES knowledge_sources(id),
    mode                VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    documents_processed INTEGER      DEFAULT 0,
    chunks_created      INTEGER      DEFAULT 0,
    errors              JSONB,
    started_at          TIMESTAMPTZ  DEFAULT NOW(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_ingestion_logs_source_id ON ingestion_logs(source_id);
CREATE INDEX idx_ingestion_logs_started ON ingestion_logs(started_at DESC);
