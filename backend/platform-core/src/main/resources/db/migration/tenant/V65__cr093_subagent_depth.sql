-- CR-093 Phase 5: 서브에이전트 자식 재귀 차단 (BIZ-109)
-- subagent_runs.depth — 부모 chain 깊이 (root = 0, 자식 = parent + 1).
-- SubagentRunner.run() 진입 시 parent_session_id 로 부모 row 조회 → depth + 1.
-- BIZ-109: 최대 깊이 3 초과 시 즉시 FAILED 반환.
ALTER TABLE subagent_runs ADD COLUMN IF NOT EXISTS depth INT NOT NULL DEFAULT 0;

-- 자식 세션 → 부모 run 조회를 위한 인덱스 (depth 카운팅 경로).
CREATE INDEX IF NOT EXISTS idx_subagent_runs_child_session ON subagent_runs(child_session_id);
