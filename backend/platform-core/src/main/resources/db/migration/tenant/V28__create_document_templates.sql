-- CR-018: 문서 템플릿 테이블
CREATE TABLE IF NOT EXISTS document_templates (
    id              VARCHAR(100)  PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    description     TEXT,
    format          VARCHAR(10)   NOT NULL,          -- pptx, docx, pdf, xlsx, csv, html, png, jpg, svg
    template_type   VARCHAR(20)   NOT NULL DEFAULT 'code',  -- 'code' | 'file'
    code_template   TEXT,                             -- code 타입: Python 코드 템플릿
    file_path       VARCHAR(500),                     -- file 타입: 저장된 파일 경로
    variables       JSONB         NOT NULL DEFAULT '[]'::jsonb,  -- [{name, type, default_value, required, description}]
    preview_base64  TEXT,                             -- 미리보기 이미지 (base64)
    tags            JSONB         DEFAULT '[]'::jsonb,
    created_by      VARCHAR(100),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_document_templates_format ON document_templates(format);
CREATE INDEX IF NOT EXISTS idx_document_templates_type ON document_templates(template_type);
