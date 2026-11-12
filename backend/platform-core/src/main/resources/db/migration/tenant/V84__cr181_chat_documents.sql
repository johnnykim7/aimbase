-- CR-181: 위젯 옆 창 문서를 대화로 고치기. 문서와 판, 고치기 지시 틀.
--
-- 문서 행은 그 문서를 처음 고칠 때 만든다. 세션을 지워도 남기려고 외래키를 두지 않는다(대화 메시지와 같다).
-- 판 번호는 지우지 않은 판끼리만 고유다. 되감기로 지운 판 번호를 다음 고치기가 다시 쓴다(BIZ-202).

CREATE TABLE IF NOT EXISTS chat_documents (
    id              UUID         PRIMARY KEY,
    session_id      VARCHAR(100) NOT NULL,
    kind            VARCHAR(10)  NOT NULL,
    name            VARCHAR(255),
    origin_turn_id  UUID,
    block_index     INT,
    lang            VARCHAR(10),
    latest_version  INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_chat_documents_kind CHECK (kind IN ('FILE', 'BLOCK')),
    CONSTRAINT ck_chat_documents_file CHECK (kind <> 'FILE' OR name IS NOT NULL),
    CONSTRAINT ck_chat_documents_block CHECK (kind <> 'BLOCK' OR (origin_turn_id IS NOT NULL AND block_index IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_documents_file
    ON chat_documents (session_id, name) WHERE kind = 'FILE';
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_documents_block
    ON chat_documents (session_id, origin_turn_id, block_index) WHERE kind = 'BLOCK';

CREATE TABLE IF NOT EXISTS chat_document_versions (
    id                   BIGSERIAL    PRIMARY KEY,
    document_id          UUID         NOT NULL,
    version              INT          NOT NULL,
    base_version         INT,
    made_by              VARCHAR(20)  NOT NULL,
    turn_id              UUID,
    content              TEXT,
    storage_path         VARCHAR(500),
    source_storage_path  VARCHAR(500),
    size_bytes           BIGINT       NOT NULL DEFAULT 0,
    sha256               VARCHAR(64)  NOT NULL,
    deleted_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_chat_document_versions_made_by CHECK (made_by IN ('ORIGINAL', 'CHAT_EDIT', 'RESTORE'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_document_versions_live
    ON chat_document_versions (document_id, version) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_chat_document_versions_turn
    ON chat_document_versions (turn_id);

-- 틀은 이번 요청의 마지막 질문 앞에 붙여 모델에 보낸다. 대화 기록에는 저장하지 않는다.
-- 자리표시자는 DocumentEditService 가 채운다. {{selection}} 은 선택한 부분이 있으면 selection 틀을 채운 글, 없으면 빈 글이다(BIZ-205).
INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('widget.document-edit.block', 1, 'widget', 'Chat Widget: Edit a document block',
 E'The user is viewing the {{lang}} document below in a side panel and asks you to change it.\nApply the request that follows to this document.\n\nReply with a short note on what you changed, then the whole changed document as exactly one fenced code block tagged {{lang}}. Do not split it into several blocks and do not leave parts out. If the request does not ask to change the document, answer it normally and write no {{lang}} code block.\n\nDocument:\n```{{lang}}\n{{content}}\n```\n\n{{selection}}Request:',
 'en', TRUE, TRUE),
('widget.document-edit.file-source', 1, 'widget', 'Chat Widget: Edit a file made by create_file',
 E'The user is viewing the file {{name}} in a side panel and asks you to change it.\nThe file was made with the create_file tool. The tool input that made it is below.\n\nApply the request that follows by calling create_file again with the same file name {{name}} and overwrite set to true, passing the input changed as asked. Keep everything the request does not mention. Then reply with a short note on what you changed. Do not create other files unless the request asks for them. If the request does not ask to change the file, answer it normally and do not call create_file.\n\nTool input:\n```json\n{{source}}\n```\n\n{{selection}}Request:',
 'en', TRUE, TRUE),
('widget.document-edit.file', 1, 'widget', 'Chat Widget: Edit a file in the work folder',
 E'The user is viewing the file {{path}} in a side panel and asks you to change it.\n\nApply the request that follows by editing that file and saving it again at the same path in the same format. Keep everything the request does not mention. Then reply with a short note on what you changed. Do not create other files unless the request asks for them. If the request does not ask to change the file, answer it normally and leave the file as it is.\n\n{{selection}}Request:',
 'en', TRUE, TRUE),
('widget.document-edit.selection', 1, 'widget', 'Chat Widget: Edit only the selected part',
 E'The user selected the part below in the side panel. Change only that part as the request asks and keep the rest of the document exactly as it is. If the same text appears more than once, change the place the user most likely means.\n\nSelected part:\n~~~~\n{{text}}\n~~~~\n\n',
 'en', TRUE, TRUE)
ON CONFLICT DO NOTHING;
