-- CR-029: Tool Patches — SafeEdit/PatchApply 승인 흐름
CREATE TABLE IF NOT EXISTS tool_patches (
    id VARCHAR(100) PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    old_content TEXT NOT NULL,
    new_content TEXT NOT NULL,
    diff_text TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_by VARCHAR(100),
    approved_by VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ DEFAULT now() + interval '24 hours'
);

CREATE INDEX IF NOT EXISTS idx_tool_patches_session ON tool_patches(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_patches_status ON tool_patches(status);

-- CR-029: Domain App Config — 도메인별 기본값
CREATE TABLE IF NOT EXISTS domain_app_config (
    id VARCHAR(100) PRIMARY KEY,
    domain_app VARCHAR(50) NOT NULL UNIQUE,
    default_context_recipe_id VARCHAR(100),
    default_tool_allowlist JSONB,
    default_tool_denylist JSONB,
    default_policy_preset JSONB,
    default_session_scope VARCHAR(20) DEFAULT 'project',
    default_runtime VARCHAR(20) DEFAULT 'llm_api',
    mcp_server_ids JSONB,
    config JSONB,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_domain_config_app ON domain_app_config(domain_app);
