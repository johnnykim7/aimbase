-- CR-030 Phase 2: Hook Architecture (PRD-189~195)
-- 훅 정의 테이블 — 이벤트별 매칭 + 실행 순서 기반 훅 관리

CREATE TABLE hook_definitions (
    id              VARCHAR(100)    PRIMARY KEY,
    name            VARCHAR(200)    NOT NULL,
    event           VARCHAR(50)     NOT NULL,
    matcher         VARCHAR(200),
    target          VARCHAR(500)    NOT NULL,
    target_type     VARCHAR(20)     NOT NULL DEFAULT 'INTERNAL',
    timeout_ms      INT             NOT NULL DEFAULT 5000,
    exec_order      INT             NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    config          JSONB,
    created_at      TIMESTAMPTZ     DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     DEFAULT NOW()
);

CREATE INDEX idx_hook_definitions_event ON hook_definitions (event);
CREATE INDEX idx_hook_definitions_active ON hook_definitions (is_active) WHERE is_active = TRUE;
