-- V23: RAG A++ 고도화 (CR-011)
-- embeddings 테이블 확장: parent-child 계층, contextual retrieval, incremental ingestion
-- rag_evaluations 테이블 신규 생성

-- 1. embeddings 테이블 확장
ALTER TABLE embeddings
    ADD COLUMN IF NOT EXISTS parent_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS context_prefix TEXT,
    ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

-- parent-child 조회용 인덱스
CREATE INDEX IF NOT EXISTS idx_embeddings_parent_id ON embeddings (parent_id) WHERE parent_id IS NOT NULL;

-- incremental ingestion 중복 검사용 인덱스
CREATE INDEX IF NOT EXISTS idx_embeddings_content_hash ON embeddings (source_id, content_hash) WHERE content_hash IS NOT NULL;

-- 2. RAG 품질 평가 결과 테이블 (RAGAS)
CREATE TABLE IF NOT EXISTS rag_evaluations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id       VARCHAR(100) NOT NULL,
    evaluation_type VARCHAR(50)  NOT NULL DEFAULT 'ragas',
    metrics         JSONB        NOT NULL DEFAULT '{}',
    config          JSONB        NOT NULL DEFAULT '{}',
    sample_count    INT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_rag_evaluations_source ON rag_evaluations (source_id, created_at DESC);

COMMENT ON TABLE rag_evaluations IS 'RAG 품질 평가 결과 (RAGAS 메트릭)';
COMMENT ON COLUMN embeddings.parent_id IS '부모 청크 ID (parent-child 계층)';
COMMENT ON COLUMN embeddings.context_prefix IS 'Contextual Retrieval용 LLM 생성 문맥 접두사';
COMMENT ON COLUMN embeddings.content_hash IS 'SHA-256 콘텐츠 해시 (incremental ingestion)';
