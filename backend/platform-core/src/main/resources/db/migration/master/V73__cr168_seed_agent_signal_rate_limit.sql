-- CR-168: 실행기 연결 신호(등록, 상태 알림) 전용 상한. 테넌트 분당 요청 한도에서 제외한 대신 테넌트마다 따로 센다(BIZ-141).
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('ratelimit.agent-signal-max-per-minute', '60', '실행기 연결 신호(등록, 상태 알림) 테넌트별 분당 상한. 넘으면 429. 테넌트 분당 요청 한도와 따로 센다 (CR-168, BIZ-141)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
