-- CR-048 PRD-301: Tool Result Storage 치환
-- 대형 Tool Result(>81920B)의 원본을 외부 저장소에 보관하고 체인에는 ref stub만 주입.
-- ReadToolResult 도구로 원본 복구. TTL 24h (세션 TTL과 일치).

CREATE TABLE IF NOT EXISTS tool_result_storage (
    result_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    full_content TEXT NOT NULL,
    summary TEXT,
    size_bytes INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '24 hours')
);

CREATE INDEX IF NOT EXISTS idx_tool_result_storage_session
    ON tool_result_storage (session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tool_result_storage_expires
    ON tool_result_storage (expires_at);

COMMENT ON TABLE tool_result_storage IS 'CR-048: 대형 tool result 외부 저장소. 체인에는 ref stub만 주입, ReadToolResult로 복구.';
COMMENT ON COLUMN tool_result_storage.result_id IS 'res_<short-uuid> 포맷';
COMMENT ON COLUMN tool_result_storage.session_id IS '소유 세션 — 타 세션 접근 시 403';
COMMENT ON COLUMN tool_result_storage.expires_at IS 'TTL 24h — 일일 스케줄러로 만료 레코드 삭제';
