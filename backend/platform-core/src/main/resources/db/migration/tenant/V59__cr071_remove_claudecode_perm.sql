-- CR-071: 권한 규칙 정규식에서 ClaudeCode 토큰 제거
-- (V57/V58 은 CR-053 등에서 사용 중이므로 V59 사용)
UPDATE permission_rules
   SET pattern = REPLACE(pattern, '|ClaudeCode', '')
 WHERE pattern LIKE '%ClaudeCode%';
