-- CR-058: 서브워크플로우 실행 부모 참조
-- SUB_WORKFLOW 스텝이 자식 실행을 별도 run 으로 생성할 때 parent_run_id / parent_step_id 를
-- 기록하여 위젯 UI 가 부모→자식 트리를 렌더할 수 있게 한다.
-- 기존 런은 NULL = 최상위 런이다. 하위 호환.

ALTER TABLE workflow_runs
    ADD COLUMN IF NOT EXISTS parent_run_id UUID NULL,
    ADD COLUMN IF NOT EXISTS parent_step_id VARCHAR(255) NULL;

CREATE INDEX IF NOT EXISTS idx_workflow_runs_parent_run_id
    ON workflow_runs(parent_run_id);

COMMENT ON COLUMN workflow_runs.parent_run_id IS
    'CR-058: 서브워크플로우 호출 시 부모 runId. NULL = 최상위 실행.';
COMMENT ON COLUMN workflow_runs.parent_step_id IS
    'CR-058: 부모 런에서 이 자식 실행을 트리거한 스텝 ID.';
