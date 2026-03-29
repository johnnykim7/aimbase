-- CR-011 / PRD-117: Claude Code 에러 패턴 관리 테이블
CREATE TABLE claude_code_error_patterns (
    id              BIGSERIAL PRIMARY KEY,
    pattern         VARCHAR(500) NOT NULL,
    error_type      VARCHAR(50)  NOT NULL,   -- AUTH_EXPIRED, RATE_LIMIT, NETWORK, TIMEOUT, MAX_TURNS, UNKNOWN
    action          VARCHAR(50)  NOT NULL,   -- NOTIFY, RETRY, CIRCUIT_BREAKER
    priority        INT          NOT NULL DEFAULT 0,  -- 높을수록 먼저 매칭
    description     VARCHAR(500),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_error_patterns_active_priority ON claude_code_error_patterns (is_active, priority DESC);

-- 기본 에러 패턴 시드 데이터
INSERT INTO claude_code_error_patterns (pattern, error_type, action, priority, description) VALUES
    ('OAuth token expired',        'AUTH_EXPIRED',  'NOTIFY',          100, 'OAuth 토큰 만료'),
    ('invalid_api_key',            'AUTH_EXPIRED',  'NOTIFY',          100, 'API 키 무효'),
    ('authentication required',    'AUTH_EXPIRED',  'NOTIFY',           90, '인증 필요'),
    ('rate_limit',                 'RATE_LIMIT',    'RETRY',            80, 'API 속도 제한'),
    ('Rate limit exceeded',        'RATE_LIMIT',    'RETRY',            80, '속도 제한 초과'),
    ('429',                        'RATE_LIMIT',    'RETRY',            70, 'HTTP 429 Too Many Requests'),
    ('connection refused',         'NETWORK',       'RETRY',            60, '연결 거부'),
    ('network error',              'NETWORK',       'RETRY',            60, '네트워크 오류'),
    ('ETIMEDOUT',                  'NETWORK',       'RETRY',            60, '연결 타임아웃'),
    ('ECONNRESET',                 'NETWORK',       'RETRY',            50, '연결 리셋'),
    ('timed out',                  'TIMEOUT',       'CIRCUIT_BREAKER',  40, '실행 타임아웃'),
    ('max turns reached',          'MAX_TURNS',     'CIRCUIT_BREAKER',  30, '최대 턴 수 초과');
