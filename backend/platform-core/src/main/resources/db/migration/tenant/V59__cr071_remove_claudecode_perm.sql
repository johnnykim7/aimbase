-- CR-071: 권한 규칙 정규식에서 ClaudeCode 토큰 제거
-- (V57/V58 은 CR-053 등에서 사용 중이므로 V59 사용)
-- (2026-04-28 보정) 컬럼명 'pattern' → 'tool_name_pattern' (V38 정의 기준)
UPDATE permission_rules
   SET tool_name_pattern = REPLACE(tool_name_pattern, '|ClaudeCode', '')
 WHERE tool_name_pattern LIKE '%ClaudeCode%';
