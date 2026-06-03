-- CR-092: 전통적 OCR (Tesseract) — 런타임 설정 기본값 seed
-- aimbase.ocr.* 카테고리를 global_config 에 추가. PlatformSettingsService 가 5분 캐시로 조회.
-- application.yml 의 @Value(":default") 와 동일한 기본값.
-- BIZ-105 (max-pages), BIZ-106 (languages 화이트리스트), BIZ-107 (fallback 임계).

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('aimbase.ocr.enabled', 'true',
     'CR-092: Tesseract OCR 전역 ON/OFF. PdfTextExtractor 및 ocr_image MCP 툴에 적용.',
     false, 'system', NOW()),
    ('aimbase.ocr.languages', 'kor+eng',
     'CR-092 BIZ-106: 기본 OCR 언어. ''+'' 결합. 화이트리스트: eng/kor/jpn/chi_sim/chi_tra/fra/deu/spa.',
     false, 'system', NOW()),
    ('aimbase.ocr.max-pages', '50',
     'CR-092 BIZ-105: read_pdf(ocr_enabled=true) 호출당 OCR 페이지 상한. 초과 시 truncated=true 반환.',
     false, 'system', NOW()),
    ('aimbase.ocr.fallback-threshold-chars', '50',
     'CR-092 BIZ-107: PdfTextExtractor 1차 텍스트 < N자 → OCR fallback 발동.',
     false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
