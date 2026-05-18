-- CR-083: SessionStore 정합성 정공 정리
-- 대화 메시지를 seq 기반 idempotent INSERT 로 전환하고 멀티블록(content_json) 보존.
--
-- 기존 문제:
--  1) appendNewMessages count race — 동시 VT 가 stale snapshot 으로 INSERT
--  2) Tool/Image/Thinking 블록 lossy — extractText 로 Text 만 저장
--  3) createdAt 정렬 비결정성 — 같은 ms 메시지 순서 뒤섞임
--
-- 해결:
--  - seq INT 추가 + UNIQUE(session_id, seq) → race 시 ON CONFLICT DO NOTHING
--  - content_json JSONB 추가 → ContentBlock 리스트 통째 보존
--  - ORDER BY seq 로 정렬 결정성 확보
--
-- 기존 레코드 backfill:
--  - seq: ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) - 1
--  - content_json: [{type:"text", text:content}] (Tool/Image 는 이미 손실)

-- ── 1. 컬럼 추가 (NULL 허용 단계) ────────────────────────────
ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS seq          INTEGER NULL,
    ADD COLUMN IF NOT EXISTS content_json JSONB   NULL;

-- ── 2. 기존 레코드 backfill ──────────────────────────────
-- seq: 세션별 createdAt 순서. id 를 보조 정렬키로 사용해 동시성 안정.
WITH ranked AS (
    SELECT id,
           (ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) - 1) AS rn
    FROM conversation_messages
    WHERE seq IS NULL
)
UPDATE conversation_messages cm
SET seq = ranked.rn
FROM ranked
WHERE cm.id = ranked.id;

-- content_json: 기존 content (텍스트) 를 단일 Text 블록 배열로 변환.
-- COMPACT_BOUNDARY 는 메타만 의미가 있으므로 content_json 은 빈 배열로 둠.
UPDATE conversation_messages
SET content_json = CASE
    WHEN message_type = 'COMPACT_BOUNDARY' THEN '[]'::jsonb
    ELSE jsonb_build_array(jsonb_build_object('type', 'text', 'text', COALESCE(content, '')))
END
WHERE content_json IS NULL;

-- ── 3. NOT NULL + UNIQUE + 인덱스 ──────────────────────────
ALTER TABLE conversation_messages
    ALTER COLUMN seq          SET NOT NULL,
    ALTER COLUMN content_json SET NOT NULL;

ALTER TABLE conversation_messages
    ADD CONSTRAINT uq_conv_msg_session_seq UNIQUE (session_id, seq);

CREATE INDEX IF NOT EXISTS idx_conv_msg_session_seq
    ON conversation_messages (session_id, seq);

COMMENT ON COLUMN conversation_messages.seq IS
    'CR-083: 세션 내 메시지 순번 (0부터). UNIQUE(session_id, seq) 로 idempotent INSERT 보장.';
COMMENT ON COLUMN conversation_messages.content_json IS
    'CR-083: ContentBlock 리스트 (Text/ToolUse/ToolResult/Image/Thinking) JSONB 직렬화. content 컬럼은 검색용 텍스트 캐시.';
