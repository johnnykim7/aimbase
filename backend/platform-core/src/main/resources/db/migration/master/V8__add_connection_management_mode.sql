-- CR-015: 키 관리 권한 체계
-- subscriptions에 connection_management_mode 컬럼 추가

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS connection_management_mode VARCHAR(30) NOT NULL DEFAULT 'TENANT_MANAGED';

COMMENT ON COLUMN subscriptions.connection_management_mode IS
    'PLATFORM_MANAGED: 슈퍼어드민 제공, TENANT_MANAGED: 테넌트 자율, HYBRID: 병용';
