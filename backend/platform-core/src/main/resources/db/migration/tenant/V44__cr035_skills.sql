-- CR-035 PRD-237: 스킬 테이블 (Tenant DB)
-- 재사용 가능한 프롬프트 + 도구 조합 경량 실행 단위

CREATE TABLE IF NOT EXISTS skills (
    id              VARCHAR(100)    PRIMARY KEY,
    name            VARCHAR(200)    NOT NULL,
    description     TEXT,
    system_prompt   TEXT            NOT NULL,
    tools           JSONB           DEFAULT '[]',    -- 허용 도구 이름 목록
    output_schema   JSONB,                           -- 출력 JSON Schema (선택)
    tags            JSONB           DEFAULT '[]',    -- 분류 태그
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_skills_active ON skills (is_active) WHERE is_active = true;
