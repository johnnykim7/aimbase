-- CR-012 / PRD-128, PRD-129: 응답 캐시 테이블
CREATE TABLE response_cache (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cache_key       VARCHAR(64)     NOT NULL,           -- SHA-256 hash (Exact Match)
    model           VARCHAR(100)    NOT NULL,
    user_message    TEXT            NOT NULL,
    response_text   TEXT            NOT NULL,
    query_embedding vector(1536),                       -- Semantic Match용 (PRD-129)
    token_count     INT,
    hit_count       INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW() + INTERVAL '1 hour'
);

CREATE UNIQUE INDEX idx_response_cache_key ON response_cache (cache_key);
CREATE INDEX idx_response_cache_expires ON response_cache (expires_at);
