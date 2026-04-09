-- CR-029: Context Recipe — 도메인별 컨텍스트 조립 레시피
CREATE TABLE IF NOT EXISTS context_recipes (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    recipe JSONB NOT NULL,
    domain_app VARCHAR(50),
    scope_type VARCHAR(20),
    priority INT DEFAULT 0,
    active BOOLEAN DEFAULT TRUE,
    created_by VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ctx_recipes_domain ON context_recipes(domain_app);
CREATE INDEX IF NOT EXISTS idx_ctx_recipes_active ON context_recipes(active);
