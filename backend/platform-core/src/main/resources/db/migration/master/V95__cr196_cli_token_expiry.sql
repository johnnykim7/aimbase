-- CR-196 PRD-641, PRD-647: 실행기 계정 1년 토큰의 상태와 만료 알림 기록. 토큰 값은 어디에도 두지 않는다(BIZ-289).

-- 만료 알림을 보낸 기록. 같은 토큰 기한과 문턱에 두 번 보내지 않으려고 둔다(BIZ-284).
CREATE TABLE IF NOT EXISTS cli_token_expiry_notices (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL,
    agent_name      VARCHAR(200) NOT NULL,
    config_dir      VARCHAR(500) NOT NULL DEFAULT '',
    expires_at      TIMESTAMPTZ  NOT NULL,
    threshold_days  INT          NOT NULL,
    channels        VARCHAR(50)  NOT NULL,
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cli_token_expiry_notices UNIQUE (tenant_id, agent_name, config_dir, expires_at, threshold_days)
);

-- 테넌트마다 서버 실행기 계정 폴더의 토큰 상태를 마지막으로 확인한 값. 플랫폼 화면이 Master DB 만 보고 모든 테넌트를 보인다(BIZ-024).
CREATE TABLE IF NOT EXISTS cli_account_tokens (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL,
    agent_name      VARCHAR(200) NOT NULL,
    config_dir      VARCHAR(500) NOT NULL DEFAULT '',
    managed_by      VARCHAR(20)  NOT NULL DEFAULT 'tenant',
    present         BOOLEAN      NOT NULL DEFAULT FALSE,
    issued_at       TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    reachable       BOOLEAN      NOT NULL DEFAULT TRUE,
    connection_ids  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    checked_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cli_account_tokens UNIQUE (tenant_id, agent_name, config_dir)
);

-- 운영에서 바꾼 값은 덮지 않는다.
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('runner.token_expiry.notify_days', '30,7,1,0', '1년 토큰 만료 며칠 전에 알릴지(쉼표). 0 은 만료 당일과 그 뒤 (CR-196, BIZ-284)', false, 'system', NOW()),
    ('runner.token_expiry.emails', '', 'Aimbase 관리 계정의 1년 토큰 만료 알림 수신 메일 주소(쉼표) (CR-196, BIZ-284)', false, 'system', NOW()),
    ('runner.token_expiry.phones', '', 'Aimbase 관리 계정의 1년 토큰 만료 알림 수신 휴대폰 번호(쉼표) (CR-196, BIZ-284)', false, 'system', NOW()),
    ('runner.token_expiry.email_template', 'AIMBASE_CLI_TOKEN_EXPIRY_EMAIL', '1년 토큰 만료 알림 bp-notification 메일 양식 코드 (CR-196)', false, 'system', NOW()),
    ('runner.token_expiry.sms_template', 'AIMBASE_CLI_TOKEN_EXPIRY_SMS', '1년 토큰 만료 알림 bp-notification 문자 양식 코드 (CR-196)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
