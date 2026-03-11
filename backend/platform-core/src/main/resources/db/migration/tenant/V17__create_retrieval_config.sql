CREATE TABLE retrieval_config (
    id                  VARCHAR(100) PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    top_k               INTEGER      DEFAULT 5,
    similarity_threshold NUMERIC(3,2) DEFAULT 0.7,
    max_context_tokens  INTEGER      DEFAULT 4000,
    search_type         VARCHAR(20)  DEFAULT 'hybrid',
    source_filters      JSONB,
    query_processing    JSONB,
    context_template    TEXT,
    is_active           BOOLEAN      DEFAULT true,
    created_at          TIMESTAMPTZ  DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  DEFAULT NOW()
);

-- 기본 검색 설정 시드
INSERT INTO retrieval_config (id, name, top_k, similarity_threshold, max_context_tokens, search_type, is_active) VALUES
    ('default', '기본 검색 설정', 5, 0.70, 4000, 'hybrid', true);
