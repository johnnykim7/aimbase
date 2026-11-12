-- CR-185 BIZ-242: 대화 승인 대기 시간. 대화 스트림, 실행기 턴, 서버와 실행기 사이 호출 한도(300초)보다 짧아야 한다.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('chat.approval-timeout-seconds', '120', '위젯 대화에서 도구 실행 전 승인을 기다리는 최대 초. 10 ~ 240 (CR-185, BIZ-242)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
