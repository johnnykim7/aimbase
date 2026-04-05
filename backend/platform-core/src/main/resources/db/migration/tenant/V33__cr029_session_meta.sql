-- CR-029: Session Meta 확장 — 세션을 "작업 문맥 저장소"로 승격
ALTER TABLE conversation_sessions
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(20) DEFAULT 'chat',
    ADD COLUMN IF NOT EXISTS runtime_kind VARCHAR(20),
    ADD COLUMN IF NOT EXISTS workspace_ref VARCHAR(500),
    ADD COLUMN IF NOT EXISTS persistent_session BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS summary_version INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS context_recipe_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_tool_chain JSONB,
    ADD COLUMN IF NOT EXISTS app_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS project_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS parent_session_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_scope ON conversation_sessions(scope_type);
CREATE INDEX IF NOT EXISTS idx_conv_sessions_app ON conversation_sessions(app_id);
CREATE INDEX IF NOT EXISTS idx_conv_sessions_parent ON conversation_sessions(parent_session_id);
