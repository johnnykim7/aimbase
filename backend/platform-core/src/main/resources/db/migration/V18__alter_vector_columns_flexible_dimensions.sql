-- V18: 벡터 컬럼 차원 가변화 (CR-012)
-- Master DB의 embeddings 테이블도 동일하게 변경

DROP INDEX IF EXISTS idx_embeddings_hnsw;
ALTER TABLE embeddings ALTER COLUMN embedding TYPE vector;
