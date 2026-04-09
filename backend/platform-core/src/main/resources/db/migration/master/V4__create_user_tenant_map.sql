-- CR-027: 사용자 email → tenant_id 매핑
-- 로그인 시 X-Tenant-Id 없이 테넌트 자동 resolve
CREATE TABLE user_tenant_map (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    tenant_id   VARCHAR(100) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_user_tenant_map_email ON user_tenant_map (email);
CREATE INDEX idx_user_tenant_map_tenant ON user_tenant_map (tenant_id);
