-- CR-046: 대화방 Soft Delete
-- conversation_sessions / conversation_messages 에 deleted_at 컬럼 추가.
-- 모든 목록/조회 쿼리는 deleted_at IS NULL 필터를 적용해야 함.

ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_conv_sessions_deleted
    ON conversation_sessions (deleted_at)
    WHERE deleted_at IS NULL;
