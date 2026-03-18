CREATE TABLE conversation_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(100) UNIQUE NOT NULL,
    user_id VARCHAR(100),
    title VARCHAR(500),
    model VARCHAR(100),
    message_count INT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(100) NOT NULL REFERENCES conversation_sessions(session_id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    tokens INT DEFAULT 0,
    model VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_conv_sessions_user ON conversation_sessions(user_id);
CREATE INDEX idx_conv_messages_session ON conversation_messages(session_id);
