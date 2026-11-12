-- CR-184: 위젯에서 만든 그림 한 장에 한 줄. 대화 기록 답 글이 aimbase-image:<id> 로 이 줄을 가리킨다.
-- 되감기와 대화 지우기는 지운 표시만 하고 파일은 남긴다(대화 메시지와 같은 원칙, BIZ-231).

CREATE TABLE IF NOT EXISTS chat_generated_images (
    id            UUID PRIMARY KEY,
    session_id    VARCHAR(100) NOT NULL,
    turn_id       UUID,
    connection_id VARCHAR(100) NOT NULL,
    model         VARCHAR(100) NOT NULL,
    prompt        TEXT NOT NULL,
    sources       JSONB NOT NULL DEFAULT '[]',
    storage_path  VARCHAR(500) NOT NULL,
    media_type    VARCHAR(50) NOT NULL,
    width         INT,
    height        INT,
    size_bytes    BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_chat_generated_images_session_id ON chat_generated_images (session_id);
CREATE INDEX IF NOT EXISTS idx_chat_generated_images_turn_id ON chat_generated_images (turn_id);
