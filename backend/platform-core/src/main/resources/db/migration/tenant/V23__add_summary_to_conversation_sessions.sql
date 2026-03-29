-- CR-012 / PRD-127: 대화 요약 텍스트 컬럼 추가
ALTER TABLE conversation_sessions ADD COLUMN summary_text TEXT;
