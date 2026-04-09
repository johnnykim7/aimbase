-- CR-038 PRD-248: 세션 브리핑 캐시 테이블
CREATE TABLE session_briefs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(255) NOT NULL,
    summary         TEXT NOT NULL,
    key_decisions   JSONB DEFAULT '[]',
    pending_items   JSONB DEFAULT '[]',
    message_count   INT NOT NULL DEFAULT 0,
    model_used      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_session_briefs_session_id ON session_briefs(session_id);
