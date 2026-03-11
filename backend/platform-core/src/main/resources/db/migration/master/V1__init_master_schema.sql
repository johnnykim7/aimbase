-- Master DB 스키마
-- 플랫폼 전체 테넌트 레지스트리, 구독, 글로벌 설정 관리

-- 테넌트 레지스트리
CREATE TABLE IF NOT EXISTS tenants (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active', -- active, suspended, deleted
    db_host         VARCHAR(255) NOT NULL,
    db_port         INTEGER      NOT NULL DEFAULT 5432,
    db_name         VARCHAR(100) NOT NULL,
    db_username     VARCHAR(100) NOT NULL,
    db_password_encrypted TEXT   NOT NULL,
    admin_email     VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 플랫폼 슈퍼어드민 계정
CREATE TABLE IF NOT EXISTS tenant_admins (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   TEXT         NOT NULL,
    display_name    VARCHAR(200),
    role            VARCHAR(20)  NOT NULL DEFAULT 'super_admin', -- super_admin, platform_admin
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 테넌트별 구독/쿼터 설정
CREATE TABLE IF NOT EXISTS subscriptions (
    tenant_id               VARCHAR(100) PRIMARY KEY REFERENCES tenants(id),
    plan                    VARCHAR(50)  NOT NULL DEFAULT 'free', -- free, starter, pro, enterprise
    llm_monthly_token_quota BIGINT       NOT NULL DEFAULT 1000000,
    max_connections         INTEGER      NOT NULL DEFAULT 5,
    max_knowledge_sources   INTEGER      NOT NULL DEFAULT 3,
    max_workflows           INTEGER      NOT NULL DEFAULT 10,
    storage_gb              INTEGER      NOT NULL DEFAULT 1,
    max_users               INTEGER      NOT NULL DEFAULT 5,
    valid_from              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    valid_until             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 플랫폼 전역 설정 (key-value)
CREATE TABLE IF NOT EXISTS global_config (
    config_key      VARCHAR(200) PRIMARY KEY,
    config_value    TEXT         NOT NULL,
    description     VARCHAR(500),
    is_encrypted    BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 테넌트별 월별 사용량 집계
CREATE TABLE IF NOT EXISTS tenant_usage_summary (
    tenant_id           VARCHAR(100) NOT NULL,
    year_month          VARCHAR(7)   NOT NULL, -- e.g. 2026-02
    total_input_tokens  BIGINT       NOT NULL DEFAULT 0,
    total_output_tokens BIGINT       NOT NULL DEFAULT 0,
    storage_used_mb     BIGINT       NOT NULL DEFAULT 0,
    api_call_count      BIGINT       NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, year_month),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);
