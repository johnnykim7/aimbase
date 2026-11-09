-- CR-174: pending_approvals 의 action_log_id 와 policy_id 는 워크플로우 HUMAN_INPUT 에서는 각각
-- workflow_runs.id 와 단계 id 를 담는 역참조 칸이다(WorkflowEngine.handleHumanInput). action_logs,
-- policies 로만 묶은 외래키가 남아 있어 HUMAN_INPUT 단계가 승인 대기 행을 저장하지 못하고 run 이
-- failed 로 끝났다(모든 테넌트, 워크플로우 승인 대기 행 0건으로 확인).
ALTER TABLE pending_approvals DROP CONSTRAINT IF EXISTS pending_approvals_action_log_id_fkey;
ALTER TABLE pending_approvals DROP CONSTRAINT IF EXISTS pending_approvals_policy_id_fkey;
CREATE INDEX IF NOT EXISTS idx_pending_approvals_action_log_id ON pending_approvals(action_log_id);
