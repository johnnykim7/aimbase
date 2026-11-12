-- CR-185: 위젯 대화 중 도구 실행 전 승인을 워크플로우 승인과 같은 표에 담는다(approval_channel = chat).
-- 워크플로우 승인 줄은 새 칸을 비워 둔다.
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS session_id      VARCHAR(100);
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS tool_name       VARCHAR(200);
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS tool_input      JSONB;
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS requested_by    VARCHAR(100);
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS request_message TEXT;

CREATE INDEX IF NOT EXISTS idx_pending_approvals_session_id ON pending_approvals(session_id);
