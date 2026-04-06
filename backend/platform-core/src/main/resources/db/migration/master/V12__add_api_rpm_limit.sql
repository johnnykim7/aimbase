-- CR-013: API Rate Limit 방어 (TokenBucket)
-- subscriptions 테이블에 분당 API 요청 한도 컬럼 추가

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS api_rpm_limit INTEGER NOT NULL DEFAULT 60;

COMMENT ON COLUMN subscriptions.api_rpm_limit IS '분당 API 요청 한도 (Requests Per Minute). free=60, starter=300, pro=1000, enterprise=0(무제한)';

-- 기존 데이터 플랜별 기본값 적용
UPDATE subscriptions SET api_rpm_limit = 60 WHERE plan = 'free';
UPDATE subscriptions SET api_rpm_limit = 300 WHERE plan = 'starter';
UPDATE subscriptions SET api_rpm_limit = 1000 WHERE plan = 'pro';
UPDATE subscriptions SET api_rpm_limit = 0 WHERE plan = 'enterprise';
