-- CR-147: 스킬 묶음 설정값과 스킬 도구 노출
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('skills.bundle-max-bytes', '20971520', '스킬 묶음 zip 크기와 푼 합계 상한 (bytes, BIZ-118)', false, 'system', NOW()),
    ('skills.bundle-max-files', '500', '스킬 묶음 파일 수 상한 (BIZ-118)', false, 'system', NOW()),
    ('skills.file-max-bytes', '10485760', '스킬 묶음 파일 하나 상한 (bytes, BIZ-118)', false, 'system', NOW()),
    ('skills.description-max-chars', '1024', 'SKILL.md 앞머리 description 글자 수 상한 (BIZ-118)', false, 'system', NOW()),
    ('skills.ignored-entries', '__MACOSX/,.DS_Store', '올릴 때 뺄 항목 (CSV). / 로 끝나면 폴더, 아니면 파일 이름', false, 'system', NOW()),
    ('skills.runnable-extensions', '.py,.js,.mjs,.sh', '실행 컨테이너가 돌리는 스크립트 확장자 (CSV). 올릴 때 scripts 폴더 경고에 쓴다', false, 'system', NOW()),
    ('skills.read-max-bytes', '262144', 'skill_read 한 번에 돌려주는 상한 (bytes)', false, 'system', NOW()),
    ('skills.inline-max-bytes', '262144', 'LLM_CALL 단계가 지시문에 붙이는 스킬 합계 상한 (bytes, BIZ-121)', false, 'system', NOW()),
    ('skills.script-timeout-seconds', '120', 'skill_run 기본 실행 시간 (초, BIZ-122)', false, 'system', NOW()),
    ('skills.script-timeout-max-seconds', '600', 'skill_run 최대 실행 시간 (초, BIZ-122)', false, 'system', NOW()),
    ('skills.script-output-max-bytes', '65536', 'skill_run stdout, stderr 각각 상한 (bytes, BIZ-122)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;

-- 노출 목록은 덮지 않고 없을 때만 덧붙인다. 운영에서 손으로 바꾼 목록을 보존한다.
UPDATE global_config SET config_value = config_value || ',skill_open', updated_at = now()
WHERE config_key = 'mcp.cli-exposed-tools' AND position(',skill_open,' in ',' || config_value || ',') = 0;

UPDATE global_config SET config_value = config_value || ',skill_read', updated_at = now()
WHERE config_key = 'mcp.cli-exposed-tools' AND position(',skill_read,' in ',' || config_value || ',') = 0;

UPDATE global_config SET config_value = config_value || ',skill_run', updated_at = now()
WHERE config_key = 'mcp.cli-exposed-tools' AND position(',skill_run,' in ',' || config_value || ',') = 0;
