-- CR-012 / PRD-130: 대화 메모리 테이블
-- CR-049: V25 중복 해소로 V25.3 로 재번호 + IF NOT EXISTS 방어 (기존 적용 테넌트에 재실행 안전).
CREATE TABLE IF NOT EXISTS conversation_memories (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(100),
    user_id         VARCHAR(100),
    memory_type     VARCHAR(30)     NOT NULL,   -- SYSTEM_RULES, LONG_TERM, SHORT_TERM, USER_PROFILE
    content         TEXT            NOT NULL,
    embedding       vector(1536),               -- LONG_TERM relevance 검색용
    relevance_score DOUBLE PRECISION,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memories_session ON conversation_memories (session_id, memory_type);
CREATE INDEX IF NOT EXISTS idx_memories_user    ON conversation_memories (user_id, memory_type);
CREATE INDEX IF NOT EXISTS idx_memories_expires ON conversation_memories (expires_at) WHERE expires_at IS NOT NULL;
