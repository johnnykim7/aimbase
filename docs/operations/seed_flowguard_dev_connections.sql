-- CR-074 후속 — flowguard_dev 테넌트 connections 시드
--
-- 적용 시점: TURN-TCP NAT 우회(CR-074) 통합 완료 후, FlowGuard 가 Aimbase 를 통해
--           LLM 을 호출하기 전에 1회 실행.
--
-- 사전 준비:
--   1) sk-ant-... API 키 발급 (Anthropic)  → 아래 ::APIKEY:: placeholder 교체
--   2) (선택) anthropic-cli 어댑터 사용 시 사용자 PC 의 Claude CLI Max/Pro 구독 로그인 완료
--
-- 실행:
--   psql -U platform -h localhost -p 5432 -d aimbase_flowguard_dev -f \
--     docs/operations/seed_flowguard_dev_connections.sql
--
-- 검증:
--   psql -U platform -h localhost -p 5432 -d aimbase_flowguard_dev \
--     -c "SELECT id, name, adapter, type, status FROM connections;"
--
-- 주의:
--   - DB 직접 INSERT 는 서버 거치지 않으므로 감사로그/캐시 갱신 안 됨.
--     운영 단계에서는 Aimbase REST API (POST /api/v1/connections) 사용을 우선.
--   - 본 SQL 은 minimal seed 용 — connection-group / fallback 정책은 별도 설정.

BEGIN;

-- 1) Claude (Anthropic) — REST API 어댑터
INSERT INTO connections (id, name, adapter, type, config, status, created_at, updated_at)
VALUES (
    'claude-sonnet-flowguard',
    'Claude Sonnet (FlowGuard)',
    'Claude (Anthropic)',
    'ANTHROPIC',
    jsonb_build_object(
        'model',     'claude-sonnet-4-6',
        'apiKey',    '::APIKEY::',
        'maxTokens', 8192
    ),
    'active',
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

-- 2) Claude CLI (Max/Pro 구독) — anthropic-cli 어댑터
--    CR-050 / CR-071 — ClaudeCliAdapter 가 사용자 PC aimbase-agent 의 RunnerController 로 호출.
--    NAT 뒤 PC 라면 CR-074 TURN-TCP 가 활성화돼야 함 (AgentConfig.turnEnabled=true).
--    이 connection 사용 시 BE 의 X-Aimbase-Agent-Id 헤더로 라우팅 → AgentRegistry.runnerEndpoint 도달.
INSERT INTO connections (id, name, adapter, type, config, status, created_at, updated_at)
VALUES (
    'claude-cli-flowguard',
    'Claude CLI (FlowGuard, Max/Pro)',
    'anthropic-cli',
    'ANTHROPIC',
    jsonb_build_object(
        'model', 'claude-sonnet-4-6'
    ),
    'connected',
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

COMMIT;

-- 사후 검증 쿼리
-- SELECT id, name, adapter, type, status FROM connections ORDER BY adapter;
