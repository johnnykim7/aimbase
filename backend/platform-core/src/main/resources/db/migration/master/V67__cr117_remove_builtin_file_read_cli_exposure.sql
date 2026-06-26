-- CR-117: CLI 노출 목록에서 builtin_file_read 제거.
--
-- 왜: extract_facts(CLI 자율주행)에서 CLI 가 PDF 를 읽을 때 자기 네이티브 Read 로 충분한데
-- builtin_file_read 까지 노출돼 있어 CLI 가 그걸 시도 → "No such tool available: builtin_file_read"
-- 실패 후 Read 로 폴백 = 같은 페이지를 두 도구로 중복 호출 + 실패 재시도 = 느림.
-- builtin_file_read 는 SessionToolRegistry 에서 CLI 네이티브 Read 의 별칭으로 의도됐으나
-- CLI MCP 자율호출 경로에선 별칭이 안 풀려 노이즈만 남는다. 노출에서 빼면 CLI 가 Read 만 쓴다.
--
-- 다른 builtin_*(grep/glob 등)은 이번 증상과 무관하므로 건드리지 않는다(최소 변경).
-- 값에서 ',builtin_file_read' 토큰만 제거(순서 무관 안전 치환).

UPDATE global_config
SET config_value = replace(config_value, ',builtin_file_read', ''),
    updated_at = now()
WHERE config_key = 'mcp.cli-exposed-tools'
  AND config_value LIKE '%builtin_file_read%';
