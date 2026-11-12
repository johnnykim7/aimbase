-- CR-179: 위젯 사용자 선호 기억 런타임 설정.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('widget.memory-enabled', 'true', '위젯 기억과 선호 사용. 끄면 내 선호, 자동 기억, 마지막에 선택한 값을 모두 쓰지 않는다 (CR-179, BIZ-184)', false, 'system', NOW()),
    ('widget.memory-model', '', '자동 기억을 뽑을 모델. 비면 그 대화 연결의 기본 모델 (CR-179, BIZ-182)', false, 'system', NOW()),
    ('widget.memory-timeout-ms', '15000', '자동 기억 뽑기 호출 시간 한도(밀리초) (CR-179)', false, 'system', NOW()),
    ('widget.memory-max-items', '30', '한 사람의 자동 기억 최대 개수. 넘으면 오래된 것부터 지운다 (CR-179, BIZ-183)', false, 'system', NOW()),
    ('widget.memory-item-max-chars', '200', '자동 기억 하나의 최대 글자 수 (CR-179, BIZ-183)', false, 'system', NOW()),
    ('widget.preferences-max-chars', '1500', '내 선호 글 최대 글자 수 (CR-179)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
