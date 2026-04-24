-- V24: Semantic Cache 테이블 (CR-011, PRD-124)
-- 쿼리 임베딩 + 응답을 캐시하여 동일/유사 질문 반복 시 DB 검색 없이 즉시 응답
-- CR-049: V24 중복 해소로 V24.3 로 재번호 + IF NOT EXISTS 방어 + HNSW 인덱스 생성을
--         차원이 고정된 경우에만 시도하도록 가드 (V25.2 이후 query_embedding 이 차원 미지정
--         `vector` 타입으로 바뀌어 있으면 HNSW 생성 불가 → skip).

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

-- 코사인 유사도 검색용 HNSW 인덱스 — 차원이 고정된 경우에만 생성.
-- V25.2(alter_vector_columns_flexible_dimensions) 이후에는 query_embedding 이 차원 미지정 vector 로
-- 바뀌어 있을 수 있으므로 DO 블록으로 가드한다.
DO $$
DECLARE
    fmt TEXT;
BEGIN
    SELECT format_type(a.atttypid, a.atttypmod)
      INTO fmt
      FROM pg_attribute a
      JOIN pg_class c ON a.attrelid = c.oid
     WHERE c.relname = 'semantic_cache'
       AND a.attname = 'query_embedding';

    IF fmt ~ '^vector\([0-9]+\)$' THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding '
             || 'ON semantic_cache USING hnsw (query_embedding vector_cosine_ops)';
    ELSE
        RAISE NOTICE 'semantic_cache.query_embedding is dimension-less (%); HNSW index skipped', fmt;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_semantic_cache_source
    ON semantic_cache (source_id, created_at DESC);

COMMENT ON TABLE semantic_cache IS 'RAG 시맨틱 캐시 — 유사 쿼리 임베딩 매칭 (BIZ-027: threshold 0.95)';
