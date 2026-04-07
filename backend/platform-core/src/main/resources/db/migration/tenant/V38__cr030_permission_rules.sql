-- CR-030 PRD-197: Permission Rules — 도구명 패턴 → 최소 권한 매핑
CREATE TABLE IF NOT EXISTS permission_rules (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    tool_name_pattern VARCHAR(500) NOT NULL,
    required_level  VARCHAR(30)   NOT NULL,
    priority        INT           NOT NULL DEFAULT 0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ   DEFAULT now(),
    updated_at      TIMESTAMPTZ   DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_permission_rules_priority
    ON permission_rules (priority DESC);

-- 기본 규칙: 읽기 도구 → READ_ONLY, 편집 도구 → RESTRICTED_WRITE, 실행 도구 → FULL
INSERT INTO permission_rules (id, name, tool_name_pattern, required_level, priority, description) VALUES
    ('perm-rule-read',  'Read-only tools',   '(?i)(FileRead|Glob|Grep|Search|List).*',  'READ_ONLY',        100, '파일 읽기/검색 도구 — READ_ONLY 권한'),
    ('perm-rule-write', 'Write tools',       '(?i)(SafeEdit|PatchApply|Write|Create).*', 'RESTRICTED_WRITE', 200, '파일 편집/생성 도구 — RESTRICTED_WRITE 권한'),
    ('perm-rule-exec',  'Execution tools',   '(?i)(Bash|Shell|Execute|ClaudeCode).*',    'FULL',             300, '셸/실행 도구 — FULL 권한')
ON CONFLICT (id) DO NOTHING;
