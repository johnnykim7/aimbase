-- V24: Semantic Cache 테이블 (CR-011, PRD-124)
-- 쿼리 임베딩 + 응답을 캐시하여 동일/유사 질문 반복 시 DB 검색 없이 즉시 응답

CREATE TABLE IF NOT EXISTS semantic_cache (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id       VARCHAR(100) NOT NULL,
    query_text      TEXT         NOT NULL,
    query_embedding vector(1536) NOT NULL,
    response_text   TEXT         NOT NULL,
    metadata        JSONB        NOT NULL DEFAULT '{}',
    hit_count       INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_hit_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ
);

-- 코사인 유사도 검색용 HNSW 인덱스
CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding
    ON semantic_cache USING hnsw (query_embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_semantic_cache_source
    ON semantic_cache (source_id, created_at DESC);

COMMENT ON TABLE semantic_cache IS 'RAG 시맨틱 캐시 — 유사 쿼리 임베딩 매칭 (BIZ-027: threshold 0.95)';
