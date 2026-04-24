-- CR-012 / PRD-127: 대화 요약 텍스트 컬럼 추가
-- CR-049: V23 중복 해소로 V23.2 로 재번호 + IF NOT EXISTS 방어 추가 (기존 적용 테넌트에 재실행 안전).
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS summary_text     TEXT;
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS last_summary_at  TIMESTAMPTZ;
