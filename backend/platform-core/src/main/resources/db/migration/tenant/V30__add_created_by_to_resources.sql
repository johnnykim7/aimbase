-- CR-022: 사용자별 리소스 소유 — created_by 컬럼 추가
-- 리소스 생성자 추적 + ?my=true 개인 필터링 지원

ALTER TABLE knowledge_sources ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE workflows         ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE projects          ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE prompts           ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE schemas           ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_sources_created_by ON knowledge_sources (created_by);
CREATE INDEX IF NOT EXISTS idx_workflows_created_by          ON workflows (created_by);
CREATE INDEX IF NOT EXISTS idx_projects_created_by           ON projects (created_by);
CREATE INDEX IF NOT EXISTS idx_prompts_created_by            ON prompts (created_by);
CREATE INDEX IF NOT EXISTS idx_schemas_created_by            ON schemas (created_by);
