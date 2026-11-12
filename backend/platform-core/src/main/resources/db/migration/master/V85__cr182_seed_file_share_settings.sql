-- CR-182: 위젯 파일 만들기, 저장, 내보내기, 공유 링크 런타임 설정.
-- 공개 주소는 비워 둔다. 비어 있으면 공유를 만들지 않아(409 share_not_configured) 주소를 정하기 전에는 링크가 나가지 않는다.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('chat.file-create.enabled', 'true', '파일 만들기 도구를 위젯 대화에 준다 (CR-182, BIZ-210)', false, 'system', NOW()),
    ('chat.file-create.max-bytes', '20971520', '만든 파일 크기 상한, 바이트 (CR-182, BIZ-210)', false, 'system', NOW()),
    ('chat.export.default-timezone', 'Asia/Seoul', '저장, 내보내기 파일 이름과 본문 시각의 기본 시간대 (CR-182, BIZ-211)', false, 'system', NOW()),
    ('chat.export.labels', '{"question":"질문","answer":"응답","attachment":"첨부","image":"그림","untitled":"대화","exported_at":"내보낸 시각","answered_at":"응답 시각","model":"모델","message_count":"메시지","shared":"공유한 대화","expires":"기한","link_gone":"링크가 없거나 끝났습니다"}', '저장, 내보내기, 공유 사본에 들어가는 문구 (CR-182)', false, 'system', NOW()),
    ('chat.share.enabled', 'true', '공유 링크를 만든다 (CR-182, BIZ-212)', false, 'system', NOW()),
    ('chat.share.public-base-url', '', '받는 사람이 여는 공개 주소. 비면 공유를 만들지 않는다 (CR-182, BIZ-212)', false, 'system', NOW()),
    ('chat.share.ttl-days', '7', '공유 링크 기본 기한, 일 (CR-182, BIZ-213)', false, 'system', NOW()),
    ('chat.share.max-ttl-days', '30', '요청으로 늘릴 수 있는 공유 기한 상한, 일 (CR-182, BIZ-213)', false, 'system', NOW()),
    ('chat.share.open-rate-per-minute', '60', '같은 주소에서 공유 링크 분당 열기 상한 (CR-182, BIZ-213)', false, 'system', NOW())
ON CONFLICT DO NOTHING;
