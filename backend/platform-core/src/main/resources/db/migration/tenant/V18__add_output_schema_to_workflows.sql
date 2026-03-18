-- CR-007: 워크플로우 구조화된 출력 스키마 (output_schema)
-- 워크플로우 실행 결과의 JSON Schema를 지정. 마지막 LLM 스텝에 자동 주입됨.
ALTER TABLE workflows ADD COLUMN output_schema JSONB;

COMMENT ON COLUMN workflows.output_schema IS '워크플로우 출력 JSON Schema (CR-007). null이면 일반 텍스트 출력.';
