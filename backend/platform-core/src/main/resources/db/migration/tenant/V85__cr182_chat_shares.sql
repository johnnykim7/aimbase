-- CR-182: 위젯 대화 공유 링크와 파일 만들기 도구 설명.
--
-- 공유 링크는 만든 순간의 사본을 가리킨다. 세션을 지워도 끊은 기록을 남기려고 대화 테이블에 외래키를 두지 않는다.
-- 기한 지난 사본을 지울 때 행은 남기고 files_purged_at 만 적는다. 지움 대상만 빨리 찾게 부분 인덱스를 둔다.

CREATE TABLE IF NOT EXISTS chat_shares (
    id               UUID          PRIMARY KEY,
    token            VARCHAR(64)   NOT NULL,
    kind             VARCHAR(20)   NOT NULL,
    session_id       VARCHAR(255)  NOT NULL,
    file_name        VARCHAR(255),
    snapshot_path    VARCHAR(500)  NOT NULL,
    snapshot_size    BIGINT        NOT NULL,
    content_type     VARCHAR(150)  NOT NULL,
    created_by       VARCHAR(255)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ   NOT NULL,
    revoked_at       TIMESTAMPTZ,
    view_count       INT           NOT NULL DEFAULT 0,
    last_viewed_at   TIMESTAMPTZ,
    files_purged_at  TIMESTAMPTZ,
    CONSTRAINT uq_chat_shares_token UNIQUE (token),
    CONSTRAINT ck_chat_shares_kind CHECK (kind IN ('CONVERSATION', 'FILE')),
    CONSTRAINT ck_chat_shares_file CHECK (kind <> 'FILE' OR file_name IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_chat_shares_session ON chat_shares (session_id, created_by);
CREATE INDEX IF NOT EXISTS idx_chat_shares_expires ON chat_shares (expires_at) WHERE files_purged_at IS NULL;

INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('tool.create-file.description', 1, 'tool', 'Tool create_file description',
 E'Create a file the user can download: Word (docx), PDF, Excel (xlsx), PowerPoint (pptx), CSV, Markdown (md), HTML or plain text (txt).\nCall this only when the user asks for a file or a document to download. Only files made with this tool are shown to the user as downloads.\n- docx, pdf, html: write the body in `content` as Markdown (headings, lists, tables, code blocks). `title` becomes the top heading.\n- md, txt: `content` is written as is.\n- xlsx: put tables in `sheets`. The first row of each sheet is the header.\n- csv: the first sheet in `sheets`, or `content` when there are no sheets.\n- pptx: put `slides`, each with a title, bullets and optional speaker notes. `title` adds a cover slide.\n`file_name` has no extension; the format adds it. To revise a file you made earlier in this conversation, call again with the same `file_name` and `overwrite` true.\nAfter the file is made, do not repeat its contents in your reply. Say briefly what you made.',
 'en', true, true)
ON CONFLICT DO NOTHING;
