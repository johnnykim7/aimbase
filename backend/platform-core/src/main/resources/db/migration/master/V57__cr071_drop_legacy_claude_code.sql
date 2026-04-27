-- CR-071 Phase 1: ClaudeCodeTool / ClaudeCliLlmAdapter 자산 정리
-- 운영 중이 아니므로 단계적 deprecation 없이 즉시 drop.

-- 시드 워크플로우 정리 (V10/V11 의 'tool: claude_code' 워크플로우)
-- 정확한 컬럼명/JSON 경로는 실제 workflows 테이블 정의에 맞춰 보정 필요
DELETE FROM workflows
 WHERE definition::text LIKE '%"tool":"claude_code"%'
    OR definition::text LIKE '%"tool": "claude_code"%';

-- ClaudeCodeTool 에러 패턴 (V5)
DROP TABLE IF EXISTS claude_code_error_patterns;

-- 에이전트 계정 (V7) — claude_code 전용
DROP TABLE IF EXISTS agent_account_assignments;
DROP TABLE IF EXISTS agent_accounts;
