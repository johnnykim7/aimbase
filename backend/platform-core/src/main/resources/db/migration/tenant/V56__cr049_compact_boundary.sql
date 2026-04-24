-- CR-049 PRD-303: Compact Boundary 메시지 마커
-- 장기 세션이 자동 압축으로 잘려나간 뒤에도 사용자가 무손실 재개할 수 있도록 압축 시점에
-- COMPACT_BOUNDARY 메시지를 삽입한다. LLM 컨텍스트 전송 시 본문은 포함되지 않고
-- 메타데이터(boundary_meta)로만 전달된다. 기존 레코드는 message_type='TEXT' 로 backfill.

ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS message_type   VARCHAR(30) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN IF NOT EXISTS boundary_meta  JSONB       NULL;

-- COMPACT_BOUNDARY 메시지만 빠르게 찾기 위한 부분 인덱스
CREATE INDEX IF NOT EXISTS idx_conv_messages_session_boundary
    ON conversation_messages(session_id)
    WHERE message_type = 'COMPACT_BOUNDARY';

COMMENT ON COLUMN conversation_messages.message_type IS
    'CR-049: TEXT / TOOL_USE / TOOL_RESULT / COMPACT_BOUNDARY';
COMMENT ON COLUMN conversation_messages.boundary_meta IS
    'CR-049: COMPACT_BOUNDARY 전용 {summary, compacted_count, tokens_saved, boundary_at}';
