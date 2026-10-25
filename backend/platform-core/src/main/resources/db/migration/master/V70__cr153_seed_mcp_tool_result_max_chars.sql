-- CR-153: 서버 MCP 통로가 도구 목록의 도구마다 적는 결과 크기 상한 (BIZ-125)
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('orchestrator.mcp-tool-result-max-chars', '200000', 'CLI 연결(/mcp, /mcp-skills)로 나가는 도구 결과를 CLI 가 그대로 받는 최대 글자 수. CLI 자체 상한 500000 (BIZ-125)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
