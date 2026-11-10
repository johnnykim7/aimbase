-- CR-175: 대화 요청 단위 번호와 되감기, 대화 제목 출처, 답 평가, 후속 질문 프롬프트 틀.
--
-- 되감기(다시 만들기, 질문 고치기)는 메시지를 지우지 않고 지운 표시만 한다(BIZ-148).
-- 지운 순번을 새 메시지가 다시 쓰도록 순번 고유를 지우지 않은 행으로만 좁힌다.
-- 지금 데이터는 모든 행에서 (session_id, seq) 가 고유하므로 부분 고유 인덱스를 바로 만들 수 있다.

ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS turn_id UUID;
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS delete_reason VARCHAR(20);

UPDATE conversation_messages
   SET delete_reason = 'SESSION_DELETE'
 WHERE deleted_at IS NOT NULL
   AND delete_reason IS NULL;

ALTER TABLE conversation_messages DROP CONSTRAINT IF EXISTS uq_conv_msg_session_seq;
CREATE UNIQUE INDEX IF NOT EXISTS uq_conv_msg_session_seq_live
    ON conversation_messages (session_id, seq)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_conv_msg_turn ON conversation_messages (turn_id);

ALTER TABLE conversation_sessions
    ADD COLUMN IF NOT EXISTS title_source VARCHAR(20) NOT NULL DEFAULT 'FIRST_MESSAGE';

-- 세션을 지워도 평가는 남긴다(관리 화면에서 지운 대화로 보인다). 그래서 외래키를 두지 않는다.
CREATE TABLE IF NOT EXISTS message_feedback (
    id           BIGSERIAL    PRIMARY KEY,
    session_id   VARCHAR(100) NOT NULL,
    turn_id      UUID         NOT NULL,
    user_ref     VARCHAR(200) NOT NULL,
    rating       VARCHAR(10)  NOT NULL,
    reason_codes JSONB        NOT NULL DEFAULT '[]'::jsonb,
    comment      TEXT,
    model        VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_message_feedback_turn_user UNIQUE (turn_id, user_ref),
    CONSTRAINT ck_message_feedback_rating CHECK (rating IN ('UP', 'DOWN'))
);
CREATE INDEX IF NOT EXISTS idx_message_feedback_created ON message_feedback (created_at);
CREATE INDEX IF NOT EXISTS idx_message_feedback_rating ON message_feedback (rating, created_at);

-- 자리표시자는 FollowUpService 가 채운다: question, answer, count, need_title(yes 또는 no).
INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('widget.follow-ups', 1, 'widget', 'Chat Widget — Follow-up questions and title',
 E'You suggest what the user may ask next in a chat with an assistant.\nRead the question and the answer below.\n\nWrite exactly {{count}} short follow-up questions the user is likely to ask next. Write them in the same language as the question. Each one must be something the assistant can answer, and must not ask again what the answer already covers.\n\nneed_title: {{need_title}}\nIf need_title is yes, also write a title for this conversation of at most 30 characters in the same language as the question. If need_title is no, set title to an empty string.\n\nReply with JSON only, no other text, in this shape:\n{"follow_ups": ["..."], "title": "..."}\n\n[Question]\n{{question}}\n\n[Answer]\n{{answer}}',
 'en', true, true)
ON CONFLICT DO NOTHING;
