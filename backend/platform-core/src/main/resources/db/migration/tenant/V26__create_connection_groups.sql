-- CR-015: 커넥션 그룹 (Connection Group)
-- 동일 프로바이더 커넥션을 그룹으로 묶어 폴백/분산 관리

CREATE TABLE connection_groups (
    id              VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    adapter         VARCHAR(50)  NOT NULL,
    strategy        VARCHAR(30)  NOT NULL DEFAULT 'PRIORITY',
    members         JSONB        NOT NULL DEFAULT '[]',
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_connection_groups_adapter ON connection_groups(adapter);
CREATE INDEX idx_connection_groups_default ON connection_groups(is_default) WHERE is_default = TRUE;
