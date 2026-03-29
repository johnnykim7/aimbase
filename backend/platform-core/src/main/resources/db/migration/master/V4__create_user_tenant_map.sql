-- 사용자-테넌트 매핑 테이블 (이메일 기반 자동 라우팅)
CREATE TABLE user_tenant_map (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255)  NOT NULL UNIQUE,
    tenant_id  VARCHAR(100)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ   DEFAULT NOW()
);

CREATE INDEX idx_user_tenant_map_email ON user_tenant_map (email);
CREATE INDEX idx_user_tenant_map_tenant ON user_tenant_map (tenant_id);
