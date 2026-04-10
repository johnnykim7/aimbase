-- CR-039 PRD-265/266: Swarm 팀 협업 테이블
-- BIZ-073: 팀당 멤버 최대 5명
-- BIZ-074: 세션당 활성 팀 최대 3개

CREATE TABLE IF NOT EXISTS teams (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(255) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    objective       TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    members         JSONB NOT NULL DEFAULT '[]'::jsonb,
    result_summary  TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    dissolved_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_teams_session_id ON teams (session_id);
CREATE INDEX idx_teams_status ON teams (status);
CREATE INDEX idx_teams_session_status ON teams (session_id, status);
