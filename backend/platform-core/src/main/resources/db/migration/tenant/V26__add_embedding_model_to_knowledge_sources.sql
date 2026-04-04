-- V26: knowledge_sources 테이블에 embedding_model 컬럼 추가
-- 각 지식 소스별로 사용하는 임베딩 모델을 명시적으로 지정 (CR-012)
ALTER TABLE knowledge_sources
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100) DEFAULT 'BAAI/bge-m3';

COMMENT ON COLUMN knowledge_sources.embedding_model IS '임베딩 모델 식별자 (BAAI/bge-m3, text-embedding-3-small, text-embedding-3-large)';
