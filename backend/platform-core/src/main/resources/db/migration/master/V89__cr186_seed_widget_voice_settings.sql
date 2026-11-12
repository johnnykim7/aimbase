-- CR-186: 위젯 답 읽어 주기와 음성 대화 런타임 설정, 위젯 토큰 권한 chat:tts, chat:voice.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('widget.tts.enabled', 'true', '위젯 답 읽어 주기 사용 (CR-186, PRD-580)', false, 'system', NOW()),
    ('widget.tts.voice', '', '위젯이 읽을 목소리. 목록 목소리 id 면 맥 엔진 먼저, 그 밖의 값은 OpenAI. 비면 목록의 기본 목소리 (CR-186, BIZ-252)', false, 'system', NOW()),
    ('widget.tts.speed', '1.0', '위젯 읽는 속도 (CR-186)', false, 'system', NOW()),
    ('widget.tts.max-chars', '300', '위젯 읽기 한 요청 글자 수 상한, 위젯 조각 상한 (CR-186, BIZ-251)', false, 'system', NOW()),
    ('widget.tts.rate-limit-per-minute', '60', '위젯 세션의 분당 읽기 요청 수 (CR-186, BIZ-251)', false, 'system', NOW()),
    ('widget.tts.local-timeout-seconds', '20', '위젯 읽기에서 맥 엔진을 기다리는 초. 넘으면 OpenAI 로 전환 (CR-186, BIZ-252)', false, 'system', NOW()),
    ('widget.voice.enabled', 'true', '위젯 음성 대화 사용 (CR-186, PRD-581)', false, 'system', NOW()),
    ('widget.voice.ticket-ttl-seconds', '30', '음성 대화 입장권 유효 초 (CR-186, BIZ-253)', false, 'system', NOW()),
    ('widget.voice.max-concurrent-streams', '3', '동시에 열 수 있는 음성 중계 수 (CR-186, BIZ-253)', false, 'system', NOW()),
    ('widget.voice.end-silence-ms', '1200', '말이 끝났다고 보는 조용한 시간 ms (CR-186, BIZ-254)', false, 'system', NOW()),
    ('widget.voice.speech-level', '0.02', '말소리로 보는 소리 크기 RMS 0~1 (CR-186, BIZ-254)', false, 'system', NOW()),
    ('widget.voice.max-utterance-seconds', '60', '한 발화 최대 초 (CR-186, BIZ-254)', false, 'system', NOW()),
    ('widget.voice.idle-end-seconds', '120', '듣는 중 말이 없으면 음성 대화를 끝내는 초 (CR-186, BIZ-254)', false, 'system', NOW()),
    ('widget.voice.final-timeout-seconds', '10', '말 끝 뒤 마지막 글을 기다리는 초 (CR-186, BIZ-253)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;

UPDATE global_config
SET config_value = config_value || ',chat:tts', updated_at = NOW(), updated_by = 'system'
WHERE config_key = 'widget.allowed-scopes' AND config_value NOT LIKE '%chat:tts%';

UPDATE global_config
SET config_value = config_value || ',chat:voice', updated_at = NOW(), updated_by = 'system'
WHERE config_key = 'widget.allowed-scopes' AND config_value NOT LIKE '%chat:voice%';
