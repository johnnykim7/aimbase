-- Master DB 인덱스

-- tenants: 상태별 조회 최적화
CREATE INDEX IF NOT EXISTS idx_tenants_status ON tenants(status);
CREATE INDEX IF NOT EXISTS idx_tenants_admin_email ON tenants(admin_email);

-- subscriptions: 플랜별 조회
CREATE INDEX IF NOT EXISTS idx_subscriptions_plan ON subscriptions(plan);

-- tenant_usage_summary: 테넌트별 월별 조회
CREATE INDEX IF NOT EXISTS idx_usage_tenant_month ON tenant_usage_summary(tenant_id, year_month DESC);

-- global_config: 키 조회 (PK가 이미 있으므로 추가 인덱스 불필요)

-- 초기 슈퍼어드민 계정 (변경 필요)
INSERT INTO tenant_admins (email, password_hash, display_name, role)
VALUES ('admin@platform.local', '$2a$10$placeholder_change_me', 'Platform Admin', 'super_admin')
ON CONFLICT (email) DO NOTHING;
