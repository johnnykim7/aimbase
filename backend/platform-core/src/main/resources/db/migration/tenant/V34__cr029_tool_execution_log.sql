-- CR-029: Tool Execution Log — 도구 실행 lineage 추적 (비교 가능성)
CREATE TABLE IF NOT EXISTS tool_execution_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(100) NOT NULL,
    workflow_run_id VARCHAR(100),
    step_id VARCHAR(100),
    turn_number INT,
    sequence_in_turn INT,
    tool_id VARCHAR(100) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    input_summary TEXT,
    input_full JSONB,
    output_summary TEXT,
    output_full TEXT,
    success BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms INT,
    artifacts JSONB,
    side_effects JSONB,
    context_snapshot JSONB,
    runtime_kind VARCHAR(20),
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tool_exec_session ON tool_execution_log(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_exec_workflow ON tool_execution_log(workflow_run_id);
CREATE INDEX IF NOT EXISTS idx_tool_exec_created ON tool_execution_log(created_at);
CREATE INDEX IF NOT EXISTS idx_tool_exec_turn ON tool_execution_log(session_id, turn_number);
