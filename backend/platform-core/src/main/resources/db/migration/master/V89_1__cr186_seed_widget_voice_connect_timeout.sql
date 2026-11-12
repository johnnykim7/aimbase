-- CR-186: 받아쓰기 서버는 모델이 준비된 뒤에야 연결을 받는다. 막 켜진 서버를 음성 중계가 기다리는 시간.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('widget.voice.connect-timeout-seconds', '30', '받아쓰기 서버가 준비될 때까지 음성 중계가 기다리는 초 (CR-186, BIZ-253)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
