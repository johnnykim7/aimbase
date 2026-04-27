-- CR-071 Phase 3: agent_registry 에 ClaudeCliRunner 정보 컬럼 추가.
-- ClaudeCliAdapter (Aimbase 서버 in-process) 가 X-Aimbase-Agent-Id 헤더로 라우팅 시
-- 본 컬럼을 보고 Runner endpoint / API Key / 가용 여부를 판단한다.

ALTER TABLE agent_registry
    ADD COLUMN IF NOT EXISTS runner_endpoint      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS runner_api_key_hash  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS runner_capability    BOOLEAN NOT NULL DEFAULT FALSE;

-- runner_capability=true 인 에이전트만 ClaudeCliAdapter 라우팅 대상.
-- runner_endpoint 예: http://user-host:8290 또는 http://localhost:8290 (서버 자체 모드)
-- runner_api_key_hash: SHA-256 hex (평문 저장 금지)

COMMENT ON COLUMN agent_registry.runner_endpoint IS 'CR-071: ClaudeCliRunner HTTP base URL';
COMMENT ON COLUMN agent_registry.runner_api_key_hash IS 'CR-071: Runner X-Api-Key 의 SHA-256 hex';
COMMENT ON COLUMN agent_registry.runner_capability IS 'CR-071: ClaudeCliAdapter 라우팅 대상 여부';
