-- CR-036 PRD-249: 프롬프트 템플릿 외부화 테이블
CREATE TABLE prompt_templates (
    key             VARCHAR(100)  NOT NULL,
    version         INTEGER       NOT NULL DEFAULT 1,
    category        VARCHAR(50)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    description     TEXT,
    template        TEXT          NOT NULL,
    variables       JSONB         DEFAULT '[]'::jsonb,
    language        VARCHAR(10)   NOT NULL DEFAULT 'en',
    is_active       BOOLEAN       NOT NULL DEFAULT true,
    is_system       BOOLEAN       NOT NULL DEFAULT false,
    created_by      VARCHAR(100),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (key, version)
);

CREATE INDEX idx_pt_category ON prompt_templates(category);
CREATE INDEX idx_pt_active   ON prompt_templates(is_active);
