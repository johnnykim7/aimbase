-- CR-021: Project 계층 도입
-- 회사(Tenant) 내 프로젝트별 리소스 스코핑

-- 프로젝트 정의
CREATE TABLE IF NOT EXISTS projects (
    id          VARCHAR(100) PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    is_active   BOOLEAN DEFAULT true,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_projects_active ON projects (is_active);

-- 프로젝트 멤버 (사용자 ↔ 프로젝트 N:M)
CREATE TABLE IF NOT EXISTS project_members (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  VARCHAR(100) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id     VARCHAR(100) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL DEFAULT 'viewer',
    created_at  TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_project_members UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_members_user ON project_members (user_id);

-- 프로젝트 리소스 할당 (Knowledge Source, Prompt, Schema ↔ 프로젝트 N:M)
CREATE TABLE IF NOT EXISTS project_resources (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      VARCHAR(100) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    resource_type   VARCHAR(50) NOT NULL,
    resource_id     VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_project_resources UNIQUE (project_id, resource_type, resource_id)
);

CREATE INDEX idx_project_resources_type ON project_resources (resource_type, resource_id);

-- workflows 테이블에 project_id 추가 (nullable = 회사 공유)
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS project_id VARCHAR(100) REFERENCES projects(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_workflows_project ON workflows (project_id);
