-- CR-071 Phase 1: ClaudeCodeTool / ClaudeCliLlmAdapter 자산 정리
-- 운영 중이 아니므로 단계적 deprecation 없이 즉시 drop.
-- (2026-04-28 보정) workflows 테이블이 master DB 에 없는 환경(신생 / 분리된 멀티테넌시)을 위해 IF EXISTS 가드 추가.

-- 시드 워크플로우 정리 (V10/V11 의 'tool: claude_code' 워크플로우)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
         WHERE table_schema = 'public' AND table_name = 'workflows'
    ) THEN
        DELETE FROM workflows
         WHERE definition::text LIKE '%"tool":"claude_code"%'
            OR definition::text LIKE '%"tool": "claude_code"%';
    END IF;
END $$;

-- ClaudeCodeTool 에러 패턴 (V5)
DROP TABLE IF EXISTS claude_code_error_patterns;

-- 에이전트 계정 (V7) — claude_code 전용
DROP TABLE IF EXISTS agent_account_assignments;
DROP TABLE IF EXISTS agent_accounts;
