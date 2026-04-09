-- CR-041: Remote Agent Registry
-- 원격 에이전트(소비앱 로컬)가 MCP 서버로 등록하고,
-- Aimbase가 필요 시 해당 에이전트의 도구를 원격 호출한다.

CREATE TABLE IF NOT EXISTS agent_registry (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_name        VARCHAR(200)  NOT NULL,
    user_id           VARCHAR(255),
    session_id        VARCHAR(255),
    public_address    VARCHAR(255)  NOT NULL,
    mcp_port          INTEGER       NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    tools_cache       JSONB         NOT NULL DEFAULT '[]'::jsonb,
    metadata          JSONB                  DEFAULT '{}'::jsonb,
    registered_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deregistered_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_agent_registry_status     ON agent_registry (status);
CREATE INDEX idx_agent_registry_user_id    ON agent_registry (user_id);
CREATE INDEX idx_agent_registry_heartbeat  ON agent_registry (status, last_heartbeat_at);
