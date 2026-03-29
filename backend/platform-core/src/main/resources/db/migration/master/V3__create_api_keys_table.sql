-- API 키 관리 테이블 (Multi-domain 인증)
CREATE TABLE api_keys (
    id           VARCHAR(100)  PRIMARY KEY,
    name         VARCHAR(200)  NOT NULL,
    key_hash     VARCHAR(200)  NOT NULL,
    key_prefix   VARCHAR(12)   NOT NULL,
    tenant_id    VARCHAR(100)  REFERENCES tenants(id),
    domain_app   VARCHAR(50)   NOT NULL,
    scope        JSONB,
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    is_active    BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_api_keys_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id, is_active);
