-- CR-035 PRD-228: Cron 스케줄 엔진 — scheduled_jobs 테이블 (Master DB)
-- 워크플로우/도구 자동 실행을 위한 Cron 작업 관리

CREATE TABLE IF NOT EXISTS scheduled_jobs (
    id              VARCHAR(100)    PRIMARY KEY,
    name            VARCHAR(200)    NOT NULL,
    cron_expression VARCHAR(100)    NOT NULL,
    target_type     VARCHAR(20)     NOT NULL,  -- WORKFLOW | TOOL
    target_id       VARCHAR(100)    NOT NULL,
    input_payload   JSONB           DEFAULT '{}',
    tenant_id       VARCHAR(100)    NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    last_run_at     TIMESTAMPTZ,
    next_run_at     TIMESTAMPTZ,
    last_run_status VARCHAR(20),               -- SUCCESS | FAILED | RUNNING
    failure_count   INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_tenant ON scheduled_jobs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_active ON scheduled_jobs (is_active) WHERE is_active = true;
