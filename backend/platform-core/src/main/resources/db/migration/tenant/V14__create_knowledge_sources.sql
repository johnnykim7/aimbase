CREATE TABLE knowledge_sources (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    config          JSONB        NOT NULL,
    chunking_config JSONB        NOT NULL,
    embedding_config JSONB       NOT NULL,
    sync_config     JSONB,
    status          VARCHAR(20)  DEFAULT 'idle',
    document_count  INTEGER      DEFAULT 0,
    chunk_count     INTEGER      DEFAULT 0,
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_knowledge_sources_type ON knowledge_sources(type);
CREATE INDEX idx_knowledge_sources_status ON knowledge_sources(status);
