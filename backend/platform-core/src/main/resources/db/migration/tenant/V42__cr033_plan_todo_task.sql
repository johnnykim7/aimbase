-- CR-033: 에이전트 구조적 사고 체계 — Plan Mode + Todo + Task
-- Phase 1~5: plans, todos 테이블 신규 + subagent_runs 확장

-- plans 테이블
CREATE TABLE IF NOT EXISTS plans (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          VARCHAR(255) NOT NULL,
    title               VARCHAR(500) NOT NULL,
    status              VARCHAR(50)  NOT NULL DEFAULT 'PLANNING',
    goals               JSONB        NOT NULL DEFAULT '[]',
    steps               JSONB        NOT NULL DEFAULT '[]',
    constraints         JSONB                 DEFAULT '[]',
    verification_result JSONB,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_plans_session_id ON plans(session_id);
CREATE INDEX IF NOT EXISTS idx_plans_status     ON plans(status);

-- todos 테이블
CREATE TABLE IF NOT EXISTS todos (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   VARCHAR(255) NOT NULL,
    content      TEXT         NOT NULL,
    active_form  TEXT,
    status       VARCHAR(50)  NOT NULL DEFAULT 'pending',
    order_index  INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_todos_session_id ON todos(session_id);

-- subagent_runs 확장 (Task 도구용)
ALTER TABLE subagent_runs ADD COLUMN IF NOT EXISTS task_description TEXT;
ALTER TABLE subagent_runs ADD COLUMN IF NOT EXISTS priority         VARCHAR(20) DEFAULT 'medium';
ALTER TABLE subagent_runs ADD COLUMN IF NOT EXISTS large_output     JSONB;
