-- CR-191 BIZ-153: 플랜 목록과 플랜별 분당 요청 한도를 운영 설정으로 둔다. 테넌트 생성과 플랜 변경이 이 값으로 한도를 채운다.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다. 값은 V12 가 플랜마다 넣었던 한도와 같다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('plan.codes', 'free,starter,pro,enterprise', '플랜 목록. 적힌 순서가 화면 순서다. 이름마다 plan.<이름>.api-rpm-limit 행이 있어야 한다 (CR-191, BIZ-153)', false, 'system', NOW()),
    ('plan.default', 'free', '플랜 없이 테넌트를 만들 때의 플랜. 플랜 목록에 있는 이름이어야 한다 (CR-191, BIZ-153)', false, 'system', NOW()),
    ('plan.free.api-rpm-limit', '60', 'free 플랜의 분당 요청 한도. 0 은 무제한. 새로 만들거나 플랜을 바꾼 테넌트부터 적용 (CR-191, BIZ-153)', false, 'system', NOW()),
    ('plan.starter.api-rpm-limit', '300', 'starter 플랜의 분당 요청 한도. 0 은 무제한. 새로 만들거나 플랜을 바꾼 테넌트부터 적용 (CR-191, BIZ-153)', false, 'system', NOW()),
    ('plan.pro.api-rpm-limit', '1000', 'pro 플랜의 분당 요청 한도. 0 은 무제한. 새로 만들거나 플랜을 바꾼 테넌트부터 적용 (CR-191, BIZ-153)', false, 'system', NOW()),
    ('plan.enterprise.api-rpm-limit', '0', 'enterprise 플랜의 분당 요청 한도. 0 은 무제한. 새로 만들거나 플랜을 바꾼 테넌트부터 적용 (CR-191, BIZ-153)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;

-- 화면이 쓰던 standard 는 플랜 목록에 없는 이름이라 starter 로 옮긴다. 한도는 그 테넌트에 맞춘 값일 수 있어 그대로 둔다.
UPDATE subscriptions SET plan = 'starter', updated_at = NOW() WHERE plan = 'standard';
