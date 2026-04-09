-- CR-040: 런타임 설정 관리 — 기본 설정값 seed
-- global_config 테이블에 플랫폼 설정 기본값을 삽입한다.
-- ON CONFLICT DO NOTHING: 이미 존재하는 키는 덮어쓰지 않는다.

-- 오케스트레이터 설정
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('orchestrator.max-tool-iterations', '30', '도구 루프 최대 횟수 — 복잡 태스크 품질에 영향', false, 'system', NOW()),
    ('orchestrator.default-max-tokens', '16000', 'Anthropic 기본 응답 최대 토큰', false, 'system', NOW()),
    ('orchestrator.tool-result-budget-bytes', '51200', '도구 결과 축약 기준 (bytes)', false, 'system', NOW()),
    ('orchestrator.tool-result-compaction-threshold', '81920', '축약 트리거 임계값 (bytes)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;

-- 세션 설정
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('session.session-ttl-hours', '24', '세션 유지 시간 (hours)', false, 'system', NOW()),
    ('session.max-messages-per-session', '500', '세션당 메시지 수 제한', false, 'system', NOW()),
    ('session.message-body-max-bytes', '32768', '메시지 본문 크기 제한 (bytes)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;

-- 컨텍스트 압축 설정
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('compaction.snip-percent', '70', 'SNIP 압축 시작 %', false, 'system', NOW()),
    ('compaction.micro-compact-percent', '85', 'MICRO_COMPACT 시작 %', false, 'system', NOW()),
    ('compaction.session-memory-percent', '91', '세션 메모리 추출 %', false, 'system', NOW()),
    ('compaction.auto-compact-percent', '93', 'AUTO_COMPACT 시작 %', false, 'system', NOW()),
    ('compaction.blocking-limit-percent', '98', '블로킹 제한 %', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
