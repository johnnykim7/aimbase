-- CR-058: Chat Widget SDK — 런타임 설정 기본값 seed
-- widget.* 카테고리를 global_config 에 추가. PlatformSettingsService 가 5분 캐시로 조회.
-- 값은 관리자 UI(CR-040) 에서 수정 가능. 보안 경계 설정이므로 변경 시 감사 로그에 기록된다.

-- 전역 허용 Origin 화이트리스트 (CSV). 비어 있으면 모든 CORS 요청이 거부된다.
-- 예: "https://oms.company.com,https://rescue.company.com"
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('widget.allowed-origins', '', '위젯 임베드를 허용할 전역 Origin 화이트리스트 (CSV). 비어 있으면 위젯 호출 전부 CORS 거부.', false, 'system', NOW()),
    ('widget.allowed-scopes', 'chat:stream,workflow:subscribe,rag:read', '위젯 토큰에 부여 가능한 scope 화이트리스트 (CSV). 요청 scope 와 교집합만 발급.', false, 'system', NOW()),
    ('widget.token-ttl-seconds', '1800', '위젯 토큰 기본 TTL (초). 기본 30분.', false, 'system', NOW()),
    ('widget.token-max-ttl-seconds', '3600', '위젯 토큰 최대 TTL 하드캡 (초). 요청 값이 이를 초과하면 cap 적용.', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
