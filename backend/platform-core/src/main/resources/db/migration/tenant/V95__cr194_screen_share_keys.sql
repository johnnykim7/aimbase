-- CR-194 화면별 지난 대화와 함께 보는 대화. 두 키 모두 붙이는 앱이 준 글자 그대로이고 aimbase 는 뜻을 해석하지 않는다(BIZ-277).
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS screen_key VARCHAR(128);
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS share_key VARCHAR(128);

-- 함께 보기 키 하나에 지우지 않은 대화는 하나다. 처음 여는 두 사람이 동시에 와도 하나만 남는다(BIZ-276).
CREATE UNIQUE INDEX IF NOT EXISTS uq_conv_sessions_share_key
    ON conversation_sessions (share_key)
    WHERE share_key IS NOT NULL AND deleted_at IS NULL;

-- 메뉴 화면의 내 대화 목록(PRD-631). 주인과 화면 키로 거르고 마지막 활동 시각 순으로 본다.
CREATE INDEX IF NOT EXISTS idx_conv_sessions_user_screen_updated
    ON conversation_sessions (user_id, screen_key, updated_at DESC)
    WHERE deleted_at IS NULL AND screen_key IS NOT NULL;
