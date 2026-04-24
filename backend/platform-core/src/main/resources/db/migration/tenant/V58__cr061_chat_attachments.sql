-- CR-061: 위젯 파일 업로드 (이미지/PDF Vision 첨부)
-- 위젯이 `POST /api/v1/chat/attachments` 로 업로드한 파일 메타를 저장한다.
-- 실제 바이너리는 StorageService (로컬/S3) 에 `widget-attachments/{session_id}/...` 경로로 저장됨.
-- 세션 TTL(24h) 과 동기화된 expires_at 기준으로 AttachmentGcScheduler 가 주기 GC.

CREATE TABLE IF NOT EXISTS chat_attachments (
    id            UUID PRIMARY KEY,
    session_id    VARCHAR(100) NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    media_type    VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    storage_path  VARCHAR(500) NOT NULL,
    pages         INTEGER,
    checksum      VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_attachments_session
    ON chat_attachments (session_id);

CREATE INDEX IF NOT EXISTS idx_chat_attachments_expires
    ON chat_attachments (expires_at);
