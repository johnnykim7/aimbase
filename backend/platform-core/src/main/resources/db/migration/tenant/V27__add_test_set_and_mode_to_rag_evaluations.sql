-- V27: RAG 평가 테이블에 test_set(질문 이력) + mode(평가 모드) 컬럼 추가
ALTER TABLE rag_evaluations ADD COLUMN IF NOT EXISTS test_set jsonb DEFAULT '[]'::jsonb;
ALTER TABLE rag_evaluations ADD COLUMN IF NOT EXISTS mode varchar(20) DEFAULT 'fast';
