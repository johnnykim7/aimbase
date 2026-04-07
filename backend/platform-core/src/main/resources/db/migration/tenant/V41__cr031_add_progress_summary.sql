-- CR-031 PRD-216: SubagentRun 진행 요약 필드 추가
ALTER TABLE subagent_runs ADD COLUMN IF NOT EXISTS progress_summary VARCHAR(500);
