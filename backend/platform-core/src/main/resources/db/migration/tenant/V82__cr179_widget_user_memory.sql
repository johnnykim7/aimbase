-- CR-179: 위젯 사용자 선호 기억.
-- 한 사람의 내 선호와 마지막에 선택한 모델, 깊게 생각하기를 둔다(PRD-440, PRD-442).
-- 자동 기억은 conversation_memories 에 USER_PROFILE, PRIVATE 로 두고 주인은 위젯 토큰 user_ref 다(BIZ-180).

CREATE TABLE IF NOT EXISTS widget_user_preferences (
    user_ref        VARCHAR(200) PRIMARY KEY,
    instructions    TEXT,
    memory_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    last_model      VARCHAR(100),
    last_thinking   BOOLEAN,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 위젯 토큰 user_ref 는 200자까지 온다.
ALTER TABLE conversation_memories ALTER COLUMN user_id TYPE VARCHAR(200);

INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('widget.memory-extract', 1, 'widget', 'Chat Widget - Extract user memories',
 E'You keep a short list of things one user of a chat assistant has said about themselves, so that later conversations can follow them.\nRead the user''s latest question and the current list below.\n\nAdd only what the user states about themselves in this question:\n- how they want answers (length, tone, format, language, how to address them)\n- lasting facts about themselves that help future answers (their role, team, the kind of work they do)\n\nNever add facts about the case or task in the question, such as order numbers, customer names, amounts, dates, addresses or what a customer asked. Never add anything the user did not state about themselves.\nIf the question changes or cancels an item in the current list, put that item''s id in remove and add the new version.\nWrite each new item as one short sentence of at most {{max_chars}} characters, in the language of the question. Do not add an item the current list already says.\n\nReply with JSON only, no other text, in this shape:\n{"add": ["..."], "remove": ["<id>"]}\nIf there is nothing to change, reply {"add": [], "remove": []}.\n\n[Current list]\n{{memories}}\n\n[Question]\n{{question}}',
 'en', true, true),
('widget.user-memory', 1, 'widget', 'Chat Widget - User preferences and memories',
 E'The user of this chat set the notes below. Follow them unless they conflict with the instructions above. When they conflict, the instructions above win.\n\n[What the user asked you to keep in mind]\n{{instructions}}\n\n[What you remember about the user from earlier conversations]\n{{memories}}',
 'en', true, true)
ON CONFLICT DO NOTHING;
