-- CR-184 위젯 그림 만들기 런타임 설정. 운영에서 바꾼 값은 덮지 않는다.
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at) VALUES
('widget.image.timeout-ms', '120000', '그림 공급처 호출 시간 상한(ms). 넘으면 끊고 image_timeout', false, 'system', NOW()),
('widget.image.max-prompt-chars', '4000', '그림 설명 글자 상한', false, 'system', NOW()),
('widget.image.max-source-images', '4', '바탕 그림 장수 상한(붙인 그림과 고칠 그림 합)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
