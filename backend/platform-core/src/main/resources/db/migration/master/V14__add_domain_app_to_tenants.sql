-- CR-023: 테넌트 소비앱 메타데이터 — domain_app 컬럼 도입
-- 소비앱 식별: AXOPM, LexFlow, ChatPilot 등

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS domain_app VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_tenants_domain_app ON tenants (domain_app);
