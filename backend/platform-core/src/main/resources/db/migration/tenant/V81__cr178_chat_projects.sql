-- CR-178: 위젯 사용자가 지난 대화를 프로젝트로 묶는다.
--
-- 위젯 프로젝트는 관리 화면 프로젝트(projects, CR-021)와 다른 테이블이다. conversation_sessions.project_id 는
-- 대화 요청 때 관리 프로젝트 단계 프롬프트 틀을 찾는 칸이라 같이 쓰면 엉뚱한 틀을 찾는다(BIZ-171).
-- 프로젝트 자료는 만료되는 채팅 첨부(chat_attachments)와 따로 둔다(BIZ-173).

CREATE TABLE IF NOT EXISTS chat_projects (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_ref    VARCHAR(200) NOT NULL,
    name         VARCHAR(100) NOT NULL,
    instructions TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_projects_owner ON chat_projects (owner_ref);

CREATE TABLE IF NOT EXISTS chat_project_files (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   UUID         NOT NULL REFERENCES chat_projects (id) ON DELETE CASCADE,
    filename     VARCHAR(255) NOT NULL,
    media_type   VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    pages        INT,
    checksum     VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_project_files_project ON chat_project_files (project_id);

-- 프로젝트를 지우면 대화는 남고 프로젝트에서만 빠진다
ALTER TABLE conversation_sessions
    ADD COLUMN IF NOT EXISTS chat_project_id UUID REFERENCES chat_projects (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_conv_sessions_user_updated
    ON conversation_sessions (user_id, updated_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_conv_sessions_chat_project
    ON conversation_sessions (chat_project_id)
    WHERE chat_project_id IS NOT NULL;

-- 저장된 질문 블록에는 첨부 번호가 없고 서버가 덧붙인 안내 글이 섞인다. 이어보기 화면에 사용자가 쓴 글과
-- 첨부 이름을 그대로 보여주려고 질문 줄에만 화면용 값을 같이 둔다. 모델에 보내는 기록에는 쓰지 않는다.
ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS display JSONB;

INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('widget.project-instructions', 1, 'widget', 'Chat Widget: Project instructions',
 E'This conversation belongs to the project "{{project_name}}".\nFollow the project instructions below in every answer of this conversation.\n\n<project_instructions>\n{{instructions}}\n</project_instructions>',
 'en', true, true)
ON CONFLICT DO NOTHING;
