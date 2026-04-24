-- CR-049 PRD-305: 프롬프트 템플릿 스코프 확장
-- 시스템 지침을 GLOBAL / TENANT / PROJECT 스코프로 관리하여 테넌트 관리자 또는
-- 프로젝트 단위로 커스텀 지침을 오버라이드 할 수 있게 한다. BIZ-098: PROJECT > TENANT > GLOBAL,
-- cascade append (replace 아닌 누적) 로 병합.
-- 기존 레코드는 모두 scope='GLOBAL', project_id=NULL 로 backfill 된다(DEFAULT 적용).

ALTER TABLE prompt_templates
    ADD COLUMN IF NOT EXISTS scope      VARCHAR(20)  NOT NULL DEFAULT 'GLOBAL',
    ADD COLUMN IF NOT EXISTS project_id VARCHAR(100) NULL;

-- scope='PROJECT' 이면 project_id 필수.
ALTER TABLE prompt_templates
    ADD CONSTRAINT ck_pt_project_id_required
    CHECK (scope <> 'PROJECT' OR project_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_pt_scope
    ON prompt_templates(scope, project_id);

COMMENT ON COLUMN prompt_templates.scope IS
    'CR-049: 적용 범위 (GLOBAL / TENANT / PROJECT). tenant DB 이므로 tenant_id 는 자동 스코프됨.';
COMMENT ON COLUMN prompt_templates.project_id IS
    'CR-049: scope=PROJECT 일 때 해당 프로젝트 ID. 그 외 NULL.';
