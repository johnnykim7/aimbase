-- CR-180 위젯 옆 창 문서 미리보기 런타임 설정. 운영에서 바꾼 값은 덮지 않는다.
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at) VALUES
('widget.preview-max-file-mb', '30', '이보다 큰 파일(MB)은 미리보기 없이 내려받기만 한다', false, 'system', NOW()),
('widget.preview-max-pages', '300', '이보다 쪽이 많으면 미리보기 없이 내려받기만 한다', false, 'system', NOW()),
('widget.preview-page-width-px', '1600', '쪽 그림 가로 픽셀', false, 'system', NOW()),
('widget.preview-convert-timeout-seconds', '90', '변환 한 번의 시간 한도(초). 동시 변환 자리를 기다리는 시간도 이 안이다', false, 'system', NOW()),
('widget.preview-convert-concurrency', '2', '동시에 도는 미리보기 변환 수', false, 'system', NOW()),
('widget.preview-sheet-max-rows', '500', '표 미리보기 시트당 최대 행', false, 'system', NOW()),
('widget.preview-sheet-max-cols', '50', '표 미리보기 시트당 최대 열', false, 'system', NOW()),
('widget.preview-html-script-sources', '["https://cdnjs.cloudflare.com","https://cdn.jsdelivr.net"]', 'HTML 실행 틀이 스크립트, 스타일, 글꼴, 그림을 불러올 수 있는 출처(JSON 목록)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
