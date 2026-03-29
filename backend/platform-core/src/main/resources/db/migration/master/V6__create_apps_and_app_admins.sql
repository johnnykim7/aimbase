-- CR-014: App-Tenant 3계층 멀티테넌시
-- App(소비앱) 레지스트리 + App 어드민 테이블

-- App(소비앱) 테이블
CREATE TABLE IF NOT EXISTS apps (
    id                    VARCHAR(100) PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL,
    description           TEXT,
    status                VARCHAR(20)  NOT NULL DEFAULT 'active',  -- active, suspended, deleted
    db_host               VARCHAR(255) NOT NULL,
    db_port               INTEGER      NOT NULL DEFAULT 5432,
    db_name               VARCHAR(100) NOT NULL,  -- aimbase_app_<appId>
    db_username           VARCHAR(100) NOT NULL,
    db_password_encrypted TEXT         NOT NULL,
    owner_email           VARCHAR(255),
    max_tenants           INTEGER      NOT NULL DEFAULT 100,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_apps_status ON apps (status);

-- App 어드민 테이블 (소비앱 관리자)
CREATE TABLE IF NOT EXISTS app_admins (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id          VARCHAR(100) NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
    email           VARCHAR(255) NOT NULL,
    password_hash   TEXT         NOT NULL,
    display_name    VARCHAR(200),
    role            VARCHAR(20)  NOT NULL DEFAULT 'app_admin',  -- app_admin, app_operator
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE(app_id, email)
);

CREATE INDEX IF NOT EXISTS idx_app_admins_app_id ON app_admins (app_id);

-- tenants 테이블에 app_id FK 추가
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS app_id VARCHAR(100) REFERENCES apps(id);
CREATE INDEX IF NOT EXISTS idx_tenants_app_id ON tenants (app_id);
