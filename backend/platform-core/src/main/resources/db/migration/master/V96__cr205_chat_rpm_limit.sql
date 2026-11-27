-- CR-205 BIZ-297, BIZ-298: 위젯 토큰 요청(채팅)을 테넌트 분당 요청 한도와 별개의 칸으로 센다.
-- 상한은 구독의 chat_rpm_limit 이고 초기값은 플랜의 plan.<플랜>.chat-rpm-limit 다.
-- 이미 있는 구독은 그 행의 api_rpm_limit 값으로 채운다. 채팅이 그때까지 쓰던 상한과 같아 요청 쪽 동작이 바뀌지 않고,
-- 테넌트 칸에서 채팅이 빠져 연동키 요청에 여유가 생긴다. DB 기본값은 두지 않는다. 새 행은 플랜 값을 넣는 코드가 채운다.

ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS chat_rpm_limit INTEGER;
UPDATE subscriptions SET chat_rpm_limit = api_rpm_limit WHERE chat_rpm_limit IS NULL;
ALTER TABLE subscriptions ALTER COLUMN chat_rpm_limit SET NOT NULL;

-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다. 값은 같은 플랜의 api-rpm-limit 와 같다.
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('plan.free.chat-rpm-limit', '60', 'free 플랜의 채팅 분당 요청 한도(위젯 토큰 요청). 0 은 무제한. 신규 테넌트와 플랜을 바꾼 테넌트부터 적용 (CR-205, BIZ-298)', false, 'system', NOW()),
    ('plan.starter.chat-rpm-limit', '300', 'starter 플랜의 채팅 분당 요청 한도(위젯 토큰 요청). 0 은 무제한. 신규 테넌트와 플랜을 바꾼 테넌트부터 적용 (CR-205, BIZ-298)', false, 'system', NOW()),
    ('plan.pro.chat-rpm-limit', '1000', 'pro 플랜의 채팅 분당 요청 한도(위젯 토큰 요청). 0 은 무제한. 신규 테넌트와 플랜을 바꾼 테넌트부터 적용 (CR-205, BIZ-298)', false, 'system', NOW()),
    ('plan.enterprise.chat-rpm-limit', '0', 'enterprise 플랜의 채팅 분당 요청 한도(위젯 토큰 요청). 0 은 무제한. 신규 테넌트와 플랜을 바꾼 테넌트부터 적용 (CR-205, BIZ-298)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;

UPDATE global_config
SET description = '플랜 목록. 적힌 순서가 화면 순서다. 이름마다 plan.<이름>.api-rpm-limit 와 plan.<이름>.chat-rpm-limit 행이 있어야 한다 (CR-191, BIZ-153, CR-205, BIZ-298)'
WHERE config_key = 'plan.codes';
