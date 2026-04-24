-- CR-060: 위젯 음성 입력 (STT) — 런타임 설정 기본값 seed
-- widget.stt.* 카테고리를 global_config 에 추가. PlatformSettingsService 가 5분 캐시로 조회.
-- 값은 관리자 UI(CR-040) 에서 수정 가능. BIZ-102/103/104 경계값.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('widget.stt.max-duration-seconds', '60',
     '위젯 STT 녹음 최대 시간(초). BIZ-102. Whisper 응답 duration 기준 사후 판정.',
     false, 'system', NOW()),
    ('widget.stt.max-size-bytes', '26214400',
     '위젯 STT 업로드 최대 바이트(기본 25MB, OpenAI Whisper API 상한). BIZ-103.',
     false, 'system', NOW()),
    ('widget.stt.allowed-mime-types', 'audio/webm,audio/mp4,audio/mpeg,audio/wav,audio/ogg',
     '허용 오디오 MIME 타입(CSV, magic number 검증 기준).',
     false, 'system', NOW()),
    ('widget.stt.rate-limit-per-minute', '10',
     '세션당 분당 STT 호출 한도. BIZ-104. Redis INCR+TTL 60s.',
     false, 'system', NOW()),
    ('widget.stt.default-language', 'auto',
     '위젯 STT 기본 언어(auto 이면 Whisper 자동 감지). ISO-639-1 코드(ko/en/ja/...) 허용.',
     false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;

-- widget.allowed-scopes 에 chat:upload, chat:stt 추가.
-- V17 에서 'chat:stream,workflow:subscribe,rag:read' 로만 seed 되어 있고
-- CR-061 에서 chat:upload 는 WidgetTokenController 하드코딩 fallback 으로만 처리되어
-- DB 값과 코드 기본값이 불일치했다. 본 마이그레이션에서 누락분을 함께 정상화한다.
-- 각 항목을 독립 append 해서 이미 있는 값은 건드리지 않는다(idempotent).
UPDATE global_config
SET config_value = config_value || ',chat:upload',
    updated_at = NOW(),
    updated_by = 'system'
WHERE config_key = 'widget.allowed-scopes'
  AND config_value NOT LIKE '%chat:upload%';

UPDATE global_config
SET config_value = config_value || ',chat:stt',
    updated_at = NOW(),
    updated_by = 'system'
WHERE config_key = 'widget.allowed-scopes'
  AND config_value NOT LIKE '%chat:stt%';
