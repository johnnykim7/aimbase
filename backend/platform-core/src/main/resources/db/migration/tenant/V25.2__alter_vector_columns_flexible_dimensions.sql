-- V25: 벡터 컬럼 차원 가변화 (CR-012)
-- vector(1536) 고정 → vector (차원 미지정)로 변경
-- BGE-M3(1024d), KoSimCSE(768d), OpenAI(1536d) 등 모델 교체 시 스키마 변경 불필요
--
-- 주의: pgvector HNSW 인덱스는 차원이 고정된 컬럼에만 생성 가능.
-- 차원 미지정 시 인덱스 없이 운영하며, 데이터 적재 후 차원 확정 시
-- 수동으로 HNSW 인덱스를 생성한다:
--   CREATE INDEX idx_embeddings_hnsw ON embeddings
--     USING hnsw ((embedding::vector(1024)) vector_cosine_ops);

-- 1. embeddings 테이블
DROP INDEX IF EXISTS idx_embeddings_hnsw;
ALTER TABLE embeddings ALTER COLUMN embedding TYPE vector;

-- 2. semantic_cache 테이블
DROP INDEX IF EXISTS idx_semantic_cache_embedding;
ALTER TABLE semantic_cache ALTER COLUMN query_embedding TYPE vector;
