-- CR-084 P4: cyclic 워크플로우 HUMAN_INPUT 일시중단 → resume 시 worklist 복원
--
-- graph_mode=cyclic 워크플로우가 HUMAN_INPUT 에서 중단되면, 중단 시점의
-- worklist(다음 실행 대기 노드들) + 누적 실행 step 수를 보존해야 재개 시
-- 처음부터 다시 돌지 않는다.
--
-- {"worklist": ["node_b","node_c"], "executed": 12}  형태.
-- NULL = DAG 모드 또는 cyclic 미중단 → resume 은 기존 doExecuteAsync 경로(하위호환).

ALTER TABLE workflow_runs ADD COLUMN IF NOT EXISTS pending_worklist jsonb;

COMMENT ON COLUMN workflow_runs.pending_worklist IS
    'CR-084 cyclic resume 복원용 — {worklist:[...], executed:N}. NULL=DAG/미중단.';
