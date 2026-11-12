-- CR-183 위젯 데이터 첨부와 계산 도구 런타임 설정. 운영에서 바꾼 값은 덮지 않는다.
INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at) VALUES
('widget.attachment.max-data-bytes', '10485760', '엑셀, CSV, TSV 한 파일 크기 상한(바이트)', false, 'system', NOW()),
('widget.attachment.max-text-bytes', '10485760', '워드, 텍스트 한 파일 크기 상한(바이트)', false, 'system', NOW()),
('widget.attachment.max-text-chars', '100000', '워드, 텍스트에서 모델에 보내는 글자 상한. 넘으면 앞부분만 보낸다', false, 'system', NOW()),
('widget.data.max-rows', '100000', '데이터 파일 표 하나의 행 상한. 넘으면 올리기를 거절한다', false, 'system', NOW()),
('widget.data.max-columns', '200', '데이터 파일 표 하나의 열 상한. 넘으면 올리기를 거절한다', false, 'system', NOW()),
('widget.data.inline-rows', '200', '모든 표 행 합이 이 이하면 데이터 요약에 전체 행을 싣는다', false, 'system', NOW()),
('widget.data.preview-rows', '20', '행 합이 inline-rows 를 넘으면 표마다 요약에 싣는 앞 행 수', false, 'system', NOW()),
('widget.data.max-result-rows', '500', '계산 도구(data_query) 결과 행 상한', false, 'system', NOW()),
('widget.data.max-result-bytes', '65536', '계산 도구(data_query) 결과 크기 상한(바이트)', false, 'system', NOW()),
('widget.data.query-timeout-seconds', '10', '계산 도구(data_query) 문장 한 번의 시간 상한(초)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
