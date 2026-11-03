-- CR-165: 음성 합성 맥 엔진과 목소리 목록 설정
-- 목록에 있는 목소리만 맥 엔진으로 가고, 목록 밖 목소리는 지금처럼 OpenAI 로만 간다.
-- 목소리 이름과 설명은 소비앱 선택 화면에 그대로 보이므로 여기 한 곳에서 바꾼다.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('tts.provider', 'local', '목록 목소리를 먼저 보낼 음성 합성 엔진. local 은 맥 음성 엔진, openai 는 맥 엔진을 부르지 않는다 (CR-165)', false, 'system', NOW()),
    ('tts.fallback-to-openai', 'true', '맥 음성 엔진이 실패하면 OpenAI 로 전환할지. false 면 503 (CR-165)', false, 'system', NOW()),
    ('tts.voices', '[{"id":"ko-female-01","name":"밝은 비서","description":"20대 여성, 밝고 친근한 말투","openai_voice":"nova","default":true}]', '음성 합성 목소리 목록(JSON 배열). id 는 맥 엔진 voices 폴더 이름, openai_voice 는 OpenAI 로 전환할 때 쓰는 목소리, default 는 하나만 true (CR-165)', false, 'system', NOW()),
    ('tts.speed-min', '0.5', '목록 목소리 요청의 speed 최솟값. 밖이면 400 (CR-165)', false, 'system', NOW()),
    ('tts.speed-max', '2.0', '목록 목소리 요청의 speed 최댓값. 밖이면 400 (CR-165)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
