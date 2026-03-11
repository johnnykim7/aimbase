CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id       VARCHAR(100) REFERENCES knowledge_sources(id) ON DELETE CASCADE,
    document_id     VARCHAR(200),
    chunk_index     INTEGER      NOT NULL,
    content         TEXT         NOT NULL,
    embedding       vector(1536),
    metadata        JSONB,
    created_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_embeddings_source_id ON embeddings(source_id);
CREATE INDEX idx_embeddings_document_id ON embeddings(document_id);
CREATE INDEX idx_embeddings_hnsw ON embeddings USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
