-- CR-196 PRD-646: 이 테넌트 소비앱 계정의 1년 토큰 만료 알림 수신자. Aimbase 관리 계정의 수신자는 Master global_config 에 둔다.
CREATE TABLE IF NOT EXISTS cli_token_recipients (
    id          BIGSERIAL PRIMARY KEY,
    channel     VARCHAR(10)  NOT NULL,
    address     VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cli_token_recipients UNIQUE (channel, address),
    CONSTRAINT ck_cli_token_recipients_channel CHECK (channel IN ('EMAIL', 'SMS'))
);
