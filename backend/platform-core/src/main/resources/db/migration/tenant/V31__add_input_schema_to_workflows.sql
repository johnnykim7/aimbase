-- 워크플로우 입력 JSON Schema 컬럼 추가
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS input_schema jsonb;
