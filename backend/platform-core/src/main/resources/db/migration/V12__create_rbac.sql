CREATE TABLE roles (
    id              VARCHAR(50)  PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    inherits        VARCHAR(50)[] DEFAULT '{}',
    permissions     JSONB        NOT NULL
);

CREATE TABLE users (
    id              VARCHAR(100) PRIMARY KEY,
    email           VARCHAR(200) UNIQUE NOT NULL,
    name            VARCHAR(100),
    role_id         VARCHAR(50)  REFERENCES roles(id),
    api_key_hash    VARCHAR(200),
    is_active       BOOLEAN      DEFAULT true,
    created_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_active ON users(is_active);

-- 기본 역할 시드
INSERT INTO roles (id, name, permissions) VALUES
    ('admin', '관리자', '{"*": ["read", "write", "delete", "execute"]}'),
    ('operator', '운영자', '{"connections": ["read", "write"], "policies": ["read", "write"], "workflows": ["read", "write", "execute"], "knowledge": ["read", "write", "execute"]}'),
    ('viewer', '뷰어', '{"*": ["read"]}');
