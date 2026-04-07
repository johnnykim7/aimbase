-- CR-034 PRD-228: 에이전트 간 메시지 테이블 + subagent_runs.agent_type 컬럼
-- Sprint 45: 멀티에이전트 협업 완성

-- 1. 에이전트 간 메시지 테이블
CREATE TABLE IF NOT EXISTS agent_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(100) NOT NULL,          -- 부모(오케스트레이터) 세션
    from_agent_id   VARCHAR(100) NOT NULL,          -- 발신 에이전트 (세션 ID 또는 "orchestrator")
    to_agent_id     VARCHAR(100) NOT NULL,          -- 수신 에이전트 (세션 ID, 에이전트명, "*" = broadcast)
    message_type    VARCHAR(30) NOT NULL DEFAULT 'TEXT',  -- TEXT, COMMAND, RESULT, ERROR
    content         TEXT NOT NULL,                   -- 메시지 본문
    metadata        JSONB,                           -- 추가 메타 (priority, tags 등)
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,  -- 수신 확인 여부
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_messages_session ON agent_messages(session_id);
CREATE INDEX idx_agent_messages_to_agent ON agent_messages(to_agent_id, is_read);
CREATE INDEX idx_agent_messages_from_agent ON agent_messages(from_agent_id);

-- 2. subagent_runs에 agent_type 컬럼 추가
ALTER TABLE subagent_runs ADD COLUMN IF NOT EXISTS agent_type VARCHAR(30) DEFAULT 'GENERAL';

CREATE INDEX IF NOT EXISTS idx_subagent_runs_agent_type ON subagent_runs(agent_type);
