-- 공용 워크플로우: 모든 테넌트가 사용 가능한 플랫폼 레벨 워크플로우
CREATE TABLE platform_workflows (
    id          VARCHAR(100) PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    category    VARCHAR(50),                          -- 분류: file_analysis, text_processing, data_transform 등
    trigger_config JSONB NOT NULL DEFAULT '{}',
    steps       JSONB NOT NULL DEFAULT '[]',
    error_handling JSONB,
    output_schema JSONB,                              -- 워크플로우 출력 JSON Schema
    input_schema  JSONB,                              -- 호출 시 필요한 입력 파라미터 스키마
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_workflows_category ON platform_workflows(category);
CREATE INDEX idx_platform_workflows_active ON platform_workflows(is_active);
