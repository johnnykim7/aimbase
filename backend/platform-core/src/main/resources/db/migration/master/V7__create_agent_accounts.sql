-- CR-015: Multi-OAuth Agent Account Pool
-- 에이전트 계정 풀 (Claude Code, Codex 등 CLI 에이전트의 OAuth/API Key 계정 관리)

CREATE TABLE agent_accounts (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    agent_type      VARCHAR(50)  NOT NULL,           -- claude_code, codex, gemini_cli, ...
    auth_type       VARCHAR(20)  NOT NULL DEFAULT 'oauth',  -- oauth | api_key
    container_host  VARCHAR(255) NOT NULL,            -- 사이드카 호스트 (docker: 서비스명, 외부: IP/DNS)
    container_port  INTEGER      NOT NULL DEFAULT 9100,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',  -- active | disabled | error
    priority        INTEGER      NOT NULL DEFAULT 0,          -- 높을수록 우선
    max_concurrent  INTEGER      NOT NULL DEFAULT 1,          -- 최대 동시 실행 수
    config          JSONB        NOT NULL DEFAULT '{}',       -- 추가 설정 (model, maxTurns 등)
    health_status   VARCHAR(20)  DEFAULT 'unknown',           -- healthy | unhealthy | unknown
    last_health_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_accounts_type_status ON agent_accounts (agent_type, status);

-- 에이전트 계정 ↔ 테넌트/앱 할당
CREATE TABLE agent_account_assignments (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(100) NOT NULL REFERENCES agent_accounts(id) ON DELETE CASCADE,
    tenant_id       VARCHAR(100),
    app_id          VARCHAR(100),
    assignment_type VARCHAR(20)  NOT NULL DEFAULT 'fixed',  -- fixed | round_robin
    priority        INTEGER      NOT NULL DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE(account_id, tenant_id, app_id)
);

CREATE INDEX idx_aaa_tenant ON agent_account_assignments (tenant_id, is_active);
CREATE INDEX idx_aaa_app ON agent_account_assignments (app_id, is_active);
