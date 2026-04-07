-- CR-030 Phase 4: Memory Scope (PRD-200)
-- conversation_memories 테이블에 scope, team_id 컬럼 추가

ALTER TABLE conversation_memories
    ADD COLUMN IF NOT EXISTS scope VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    ADD COLUMN IF NOT EXISTS team_id VARCHAR(100);

-- 복합 인덱스: 팀 메모리 조회 최적화
CREATE INDEX IF NOT EXISTS idx_memories_team_scope
    ON conversation_memories (team_id, scope)
    WHERE team_id IS NOT NULL;

-- scope별 조회 인덱스
CREATE INDEX IF NOT EXISTS idx_memories_scope
    ON conversation_memories (scope);
